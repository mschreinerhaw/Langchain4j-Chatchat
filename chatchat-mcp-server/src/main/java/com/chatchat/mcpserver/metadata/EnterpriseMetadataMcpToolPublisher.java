package com.chatchat.mcpserver.metadata;

import com.chatchat.mcpserver.mcp.McpToolApplicability;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnterpriseMetadataMcpToolPublisher {

    public static final String TOOL_NAME = "enterprise_metadata_search";

    private final McpSyncServer mcpSyncServer;
    private final EnterpriseMetadataSearchService searchService;
    private final EnterpriseMetadataProperties properties;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isEnabled()) {
            refresh();
        }
    }

    public synchronized void refresh() {
        remove();
        mcpSyncServer.addTool(specification());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Enterprise metadata MCP capability registered tool={}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("Enterprise metadata search")
            .description("Search configured enterprise standard fields, business roots and code dictionaries. "
                + "Every invocation performs the required standard-field, term-root and dictionary retrieval internally; "
                + "Use this read-only capability when a task needs enterprise field meaning, technical names, "
                + "data types, standard definitions or business-term mapping. It does not create tables, "
                + "generate SQL or execute a workflow. Treat results and evidenceObjects as the factual boundary; "
                + "never invent fields that were not returned.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                    Map<String, Object> result = searchService.search(new EnterpriseMetadataSearchService.SearchRequest(
                        text(arguments.get("query")),
                        strings(arguments.get("types")),
                        strings(arguments.get("statuses")),
                        strings(arguments.get("scenarios")),
                        integer(arguments.get("limit"))
                    ));
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(summary(result))
                        .structuredContent(result)
                        .isError(false)
                        .build();
                } catch (Exception ex) {
                    Map<String, Object> error = Map.of(
                        "schemaVersion", EnterpriseMetadataSearchService.RESULT_SCHEMA_VERSION,
                        "success", false,
                        "error", ex.getMessage()
                    );
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(error)
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", mapOf(
            "query", Map.of(
                "type", "string",
                "description", "Business phrase, Chinese field name, English field name, abbreviation or dictionary meaning"
            ),
            "types", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum",
                    List.of("metadata_field", "metadata_term", "metadata_dictionary")),
                "description", "Optional type hints. The tool still performs the required standard-field, term-root and dictionary retrieval internally."
            ),
            "statuses", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional source status filters such as 标准、草案 or 启用"
            ),
            "scenarios", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional configured business scenario codes such as customer_account or risk_compliance"
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", properties.getMaxResults(),
                "description", "Maximum results"
            )
        ), List.of("query"), false, null, null);
    }

    private Map<String, Object> meta() {
        return mapOf(
            "schemaVersion", EnterpriseMetadataSearchService.RESULT_SCHEMA_VERSION,
            "kind", "enterprise_metadata_capability",
            "capabilityType", "metadata",
            "provider", "configured_catalog",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "readOnly", true,
            "riskLevel", "low",
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            McpToolApplicability.META_KEY, McpToolApplicability.of(
                "enterprise_metadata:search",
                "Enterprise data standard and business terminology retrieval",
                List.of("enterprise_metadata", "data_model", "sql_datasource"),
                "Retrieve enterprise-approved field, term and dictionary definitions as structured evidence.",
                List.of(
                    "Understand a business field before proposing schema or SQL",
                    "Find standard technical field names and data types",
                    "Map business terminology to enterprise roots or code dictionaries"
                ),
                List.of(
                    "Creating or altering a table",
                    "Executing SQL",
                    "Guessing fields not present in the returned evidence",
                    "Returning real data samples or sensitive field values"
                )
            ),
            "logicalIndexes", List.of(
                "enterprise_field_catalog",
                "enterprise_term_dictionary"
            ),
            "physicalIndex", properties.getIndexName(),
            "evidenceContract", mapOf(
                "resultPath", "evidenceObjects[]",
                "types", List.of("metadata_field", "metadata_term", "metadata_dictionary"),
                "factBoundary", "returned_records_only",
                "requiredRetrieval", "metadata_field+metadata_term+metadata_dictionary"
            )
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String summary(Map<String, Object> result) {
        return "Enterprise metadata search completed: count=" + result.getOrDefault("count", 0)
            + ", backend=" + result.getOrDefault("backend", "unknown")
            + ". Use structured results and evidenceObjects as the factual boundary.";
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private void remove() {
        try {
            mcpSyncServer.removeTool(TOOL_NAME);
        } catch (Exception ex) {
            log.debug("Enterprise metadata MCP tool was not registered: {}", ex.getMessage());
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
