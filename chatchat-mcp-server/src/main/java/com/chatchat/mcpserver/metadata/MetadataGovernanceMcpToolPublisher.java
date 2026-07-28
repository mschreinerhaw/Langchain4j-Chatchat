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
public class MetadataGovernanceMcpToolPublisher {

    public static final String ANNOTATE_TOOL = "enterprise_metadata_annotate_ddl";
    public static final String COMPARE_TOOL = "enterprise_metadata_compare";

    private final McpSyncServer mcpSyncServer;
    private final MetadataGovernanceAnalysisService analysisService;
    private final EnterpriseMetadataProperties properties;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isEnabled()) refresh();
    }

    public synchronized void refresh() {
        remove(ANNOTATE_TOOL);
        remove(COMPARE_TOOL);
        mcpSyncServer.addTool(annotationSpecification());
        mcpSyncServer.addTool(comparisonSpecification());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Enterprise metadata governance capabilities registered tools={},{}", ANNOTATE_TOOL, COMPARE_TOOL);
    }

    private McpServerFeatures.SyncToolSpecification annotationSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(ANNOTATE_TOOL)
            .title("Annotate CREATE TABLE with enterprise metadata")
            .description("Parse one CREATE TABLE statement without executing it, then annotate every physical column "
                + "using the maintained enterprise standard-field, term-root and dictionary catalog. "
                + "Returns deterministic matches, confidence, unmatched name terms and catalog evidence.")
            .inputSchema(new McpSchema.JsonSchema("object", mapOf(
                "ddl", Map.of(
                    "type", "string",
                    "description", "One CREATE TABLE statement. It is parsed only and is never executed."
                )
            ), List.of("ddl"), false, null, null))
            .meta(meta("enterprise_metadata:annotate_ddl",
                MetadataGovernanceAnalysisService.ANNOTATION_SCHEMA_VERSION))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> result(() -> {
                Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                return analysisService.annotateDdl(text(arguments.get("ddl")));
            }))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification comparisonSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(COMPARE_TOOL)
            .title("Compare table schema with enterprise metadata standards")
            .description("Compare either a supplied CREATE TABLE statement or an existing registered physical table "
                + "against maintained standard fields, term roots and dictionaries. Exactly one of ddl or tableName "
                + "should be supplied. Existing-table mode first retrieves actual columns from SQL metadata and never "
                + "queries business data.")
            .inputSchema(new McpSchema.JsonSchema("object", mapOf(
                "ddl", Map.of("type", "string", "description", "Optional CREATE TABLE statement to compare"),
                "tableName", Map.of("type", "string", "description", "Optional registered physical table name"),
                "database", Map.of("type", "string", "description", "Optional database/schema filter"),
                "assetId", Map.of("type", "string", "description", "Optional registered datasource asset id"),
                "assetName", Map.of("type", "string", "description", "Optional registered datasource asset name"),
                "env", Map.of("type", "string", "description", "Optional datasource environment")
            ), List.of(), false, null, null))
            .meta(meta("enterprise_metadata:compare_schema",
                MetadataGovernanceAnalysisService.COMPARISON_SCHEMA_VERSION))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> result(() -> {
                Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                String ddl = text(arguments.get("ddl"));
                String tableName = text(arguments.get("tableName"));
                if (ddl != null && tableName != null) {
                    throw new IllegalArgumentException("Supply ddl or tableName, not both");
                }
                if (ddl != null) return analysisService.compareDdl(ddl);
                if (tableName != null) return analysisService.compareRegisteredTable(arguments);
                throw new IllegalArgumentException("Either ddl or tableName is required");
            }))
            .build();
    }

    private McpSchema.CallToolResult result(AnalysisCall call) {
        try {
            Map<String, Object> structured = call.run();
            return McpSchema.CallToolResult.builder()
                .addTextContent("Metadata governance analysis completed: table="
                    + structured.getOrDefault("table", "-") + ", columns="
                    + structured.getOrDefault("columnCount", 0) + ", differences="
                    + structured.getOrDefault("differenceCount", 0) + ".")
                .structuredContent(structured)
                .isError(false)
                .build();
        } catch (Exception ex) {
            return McpSchema.CallToolResult.builder()
                .addTextContent(ex.getMessage())
                .structuredContent(Map.of("success", false, "error", ex.getMessage()))
                .isError(true)
                .build();
        }
    }

    private Map<String, Object> meta(String capabilityId, String schemaVersion) {
        return mapOf(
            "schemaVersion", schemaVersion,
            "kind", "enterprise_metadata_governance",
            "capabilityType", "metadata",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "readOnly", true,
            "riskLevel", "low",
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            McpToolApplicability.META_KEY, McpToolApplicability.of(
                capabilityId,
                "Enterprise metadata annotation and conformance analysis",
                List.of("enterprise_metadata", "data_model", "sql_datasource"),
                "Produce evidence-backed metadata annotations or schema differences.",
                List.of("Annotate a CREATE TABLE draft", "Assess an existing registered table"),
                List.of("Executing DDL", "Changing a physical schema", "Inventing absent standards")
            ),
            "evidenceContract", mapOf(
                "columnEvidencePath", "columns[]",
                "differenceEvidencePath", "differences[]",
                "catalogEvidencePath", "catalogEvidence",
                "factBoundary", "maintained_enterprise_metadata_catalog"
            )
        );
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Metadata governance MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    @FunctionalInterface
    private interface AnalysisCall {
        Map<String, Object> run();
    }
}
