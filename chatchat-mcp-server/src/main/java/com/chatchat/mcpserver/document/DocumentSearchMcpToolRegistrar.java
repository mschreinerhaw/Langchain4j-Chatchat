package com.chatchat.mcpserver.document;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.mcp.service.McpResultKind;
import com.chatchat.common.mcp.service.McpResultProvenance;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.chatchat.tools.mcp.McpServerToolRegistrar;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes document retrieval as an MCP-owned capability backed directly by the retrieval kernel. */
@Component
@RequiredArgsConstructor
public class DocumentSearchMcpToolRegistrar implements McpServerToolRegistrar {

    public static final String TOOL_NAME = "document_search";
    private static final String RESULT_SCHEMA = "document_evidence_v1";
    private static final String GUIDANCE = String.join(" ",
        "Retrieves bounded document evidence chunks for grounded answers.",
        "Use a concise query containing exact titles, entities, codes, versions, dates or domain terms.",
        "Empty results are valid evidence outcomes; refine at most once and never perform an exhaustive scan.",
        "Use returned citations and snippets as the sole grounding source."
    );

    private final DocumentSearchEvidenceService evidenceService;
    private final DocumentSearchRequestMapper requestMapper;
    private final Environment environment;

    @Override
    public void registerTools(ToolRegistry toolRegistry) {
        int defaultTopK = environment.getProperty("chatchat.mcp.server.document-search.default-limit", Integer.class, 8);
        int maxTopK = environment.getProperty("chatchat.mcp.server.document-search.max-limit", Integer.class, 20);
        ToolMetadata metadata = ToolMetadata.builder()
            .id(TOOL_NAME)
            .title("Document Evidence Search")
            .description(GUIDANCE)
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(List.of("search", "knowledge-base", "document"))
            .category("knowledge_document_search")
            .riskLevel(environment.getProperty("chatchat.mcp.server.document-search.risk-level", "medium"))
            .operationType("read")
            .runtimeLevel(environment.getProperty("chatchat.mcp.server.document-search.runtime-level", "readonly"))
            .userVisible(true)
            .confirmation(Map.of(
                "default", environment.getProperty(
                    "chatchat.mcp.server.document-search.confirmation-default", "ask_before_execute"),
                "allow_user_override", true
            ))
            .inputPolicy(Map.of("must_show_parameters", true, "allow_auto_fill", true,
                "sensitive_params", List.of("fileIds")))
            .outputPolicy(Map.of("mask_fields", List.of(), "max_rows_without_confirm", maxTopK))
            .outputType("json")
            .returnDirect(false)
            .timeoutMillis(environment.getProperty("chatchat.mcp.server.document-search.timeout-ms", Long.class, 300000L))
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder().name("query").type("string").description(GUIDANCE)
                    .required(true).minLength(1).maxLength(1000).build(),
                ToolParameter.builder().name("topK").type("integer").description("Maximum evidence chunks to return.")
                    .required(false).defaultValue(defaultTopK).minimum(1).maximum(maxTopK).build(),
                ToolParameter.builder().name("fileIds").type("array").description("Optional allowed document IDs.")
                    .required(false).metadata(Map.of("items", Map.of("type", "string"))).build(),
                ToolParameter.builder().name("filters").type("object").description("Optional document metadata and chunk filters.")
                    .required(false).metadata(Map.of("additionalProperties", true)).build(),
                ToolParameter.builder().name("tenantId").type("string").description("Tenant permission scope.").required(false).build(),
                ToolParameter.builder().name("userId").type("string").description("User permission scope.").required(false).build(),
                ToolParameter.builder().name("roles").type("array").description("Role permission scope.")
                    .required(false).metadata(Map.of("items", Map.of("type", "string"))).build(),
                ToolParameter.builder().name("debug").type("boolean").description("Include retrieval diagnostics.")
                    .required(false).defaultValue(false).build()
            ))
            .tags(List.of("search", "document", "knowledge-base", "agent"))
            .metadata(Map.of(
                "readOnly", true,
                "resultContract", "document_evidence_chunks",
                "contractVersion", RESULT_SCHEMA,
                McpServiceResult.RESULT_KIND_KEY, McpResultKind.DOCUMENT.name(),
                McpServiceResult.RESULT_SCHEMA_REF_KEY, RESULT_SCHEMA,
                "paginationSupported", false
            ))
            .build();

        toolRegistry.registerTool(TOOL_NAME, metadata, new LocalDocumentSearchTool(defaultTopK));
    }

    private final class LocalDocumentSearchTool implements ToolRegistry.EnhancedTool {
        private final int defaultTopK;

        private LocalDocumentSearchTool(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        @Override
        public ToolMetadata getMetadata() {
            return null;
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                DocumentSearchRequest request = requestMapper.map(
                    input == null ? Map.of() : input.getParameters(), defaultTopK);
                DocumentSearchResult result = evidenceService.search(request);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(McpServiceResult.RESULT_KIND_KEY, McpResultKind.DOCUMENT.name());
                metadata.put(McpServiceResult.RESULT_SCHEMA_REF_KEY,
                    result.contractVersion() == null || result.contractVersion().isBlank()
                        ? RESULT_SCHEMA : result.contractVersion());
                metadata.put(McpServiceResult.PROVENANCE_KEY, new McpResultProvenance(
                    "knowledge-base://document-index",
                    null,
                    Instant.now().toString(),
                    "sha256:" + ModelProtocolJson.sha256Hex(request),
                    Map.of("offset", 0, "returnedCount", Math.max(0, result.total())),
                    filterSummary(request)
                ));
                return ToolOutput.builder()
                    .success(true)
                    .data(result)
                    .message("Document evidence search completed successfully")
                    .metadata(metadata)
                    .build();
            } catch (IllegalArgumentException exception) {
                return ToolOutput.failure(exception.getMessage());
            }
        }

        private Map<String, Object> filterSummary(DocumentSearchRequest request) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("requestedFileCount", request.fileIds() == null ? 0 : request.fileIds().size());
            summary.put("selectedFileCount", request.selectedFileIds() == null ? 0 : request.selectedFileIds().size());
            summary.put("selectedDocumentCount",
                request.selectedDocumentIds() == null ? 0 : request.selectedDocumentIds().size());
            summary.put("visibilityEnforced", Boolean.TRUE.equals(request.documentVisibilityEnforced()));
            if (request.filters() != null) summary.put("documentFiltersApplied", true);
            return summary;
        }
    }
}
