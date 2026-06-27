package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.routing.TargetKindRegistry;
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
public class TemplateDiscoveryMcpToolPublisher {

    public static final String TOOL_NAME = "template_query";

    private final McpSyncServer mcpSyncServer;
    private final CommandTemplateDiscoveryService templateDiscoveryService;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove(TOOL_NAME);
        mcpSyncServer.addTool(templateQueryTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Template discovery MCP tool refreshed: {}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification templateQueryTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("Execution template discovery")
            .description("Read-only MCP Template Retrieval Engine v2 for querying registered SSH, SQL, HTTP, and business database query templates. "
                + "It resolves assets, normalizes intent, ranks authorized templates with no-vector feature scoring, "
                + "requires model-generated bilingual Chinese and English retrieval signals when intent is available, "
                + "and returns queryIr/resolutionTrace plus template metadata, risk level, parameter schema and routing hints. "
                + "It never returns raw commands, SQL, URLs, or bodies.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = templateDiscoveryService.query(request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("Command template query completed")
                        .structuredContent(result)
                        .isError(false)
                        .build();
                } catch (TargetKindRegistry.TargetKindException ex) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(errorResult(ex))
                        .isError(true)
                        .build();
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(errorResult(ex.getMessage()))
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", mapOf(
            "schemaVersion", Map.of("type", "string", "description", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION),
            "assetType", Map.of(
                "type", "string",
                "description", "Template target asset type derived from targetKind: ssh_host, sql_datasource, http_endpoint, or database_query."
            ),
            "targetKind", Map.of(
                "type", "string",
                "description", "Legacy single semantic target marker. Prefer candidates[] + finalDecision in Routing Spec v1.1."
            ),
            "finalDecision", Map.of(
                "type", "string",
                "description", "Runtime-selected targetKind from candidates[]: host, database, http, business_database_query, or document. document must use document_search instead of template_query."
            ),
            "candidates", Map.of(
                "type", "array",
                "description", "Routing candidate set proposed by planner/model; runtime validates feasibility and scores candidates before accepting finalDecision.",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "targetKind", Map.of("type", "string"),
                        "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)
                    ),
                    "required", List.of("targetKind", "confidence")
                )
            ),
            "confidence", Map.of(
                "type", "number",
                "minimum", 0,
                "maximum", 1,
                "description", "Model confidence for targetKind. Values below 0.6 return REVIEW_REQUIRED and do not retrieve templates."
            ),
            "filtersSchemaVersion", Map.of(
                "type", "string",
                "description", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            ),
            "filters", Map.of(
                "type", "object",
                "description", "Logical filters such as assetName, env, cluster, service, target, intent, bilingualIntent, intentZh, intentEn, category, labels, or queryLanguage/language. When intent is present, the model should generate both Chinese and English retrieval terms.",
                "additionalProperties", true
            ),
            "bilingualIntent", Map.of(
                "type", "array",
                "description", "Model-generated bilingual retrieval terms for template matching. Include both Chinese and English phrases, for example [\"数据库状态\", \"database health status\"].",
                "items", Map.of("type", "string")
            ),
            "bilingualQuery", Map.of(
                "type", "string",
                "description", "Alias for model-generated bilingual retrieval text. Prefer bilingualIntent[] for multiple terms."
            ),
            "intentZh", Map.of(
                "type", "string",
                "description", "Chinese retrieval phrase generated by the model for template matching."
            ),
            "intentEn", Map.of(
                "type", "string",
                "description", "English retrieval phrase generated by the model for template matching."
            ),
            "language", Map.of(
                "type", "string",
                "description", "Optional query language hint: zh, en, or auto. Template retrieval expands Chinese and English intent synonyms either way."
            ),
            "queryLanguage", Map.of(
                "type", "string",
                "description", "Alias of language for model-generated template retrieval requests."
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for logical filters; concrete target fields and raw commands are forbidden",
                "additionalProperties", true
            ),
            "trace", Map.of(
                "type", "object",
                "description", "Required replay trace such as plannerVersion, model, promptVersion, or taskId",
                "additionalProperties", true
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", CommandTemplateDiscoveryService.MAX_LIMIT,
                "description", "Maximum number of templates returned; capped at 20"
            ),
            "view", Map.of(
                "type", "string",
                "description", "Optional response view: model or system. v1 always returns the canonical templates[] representation without raw execution spec."
            )
        ), List.of("filters", "trace"), false, null, null);
    }

    private Map<String, Object> meta() {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "kind", "template_discovery_tool",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "controlPlane", "discovery",
            "readOnly", true,
            "risk_level", "low",
            "riskLevel", "low",
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            "resultShape", mapOf(
                "canonical", "templates[]",
                "templateIdPath", "templates[].templateId",
                "queryIrPath", "queryIr",
                "decisionTracePath", "resolutionTrace"
            ),
            "decisionEngine", "mcp_template_lucene_decision_v2_no_vector",
            "languageSupport", mapOf(
                "mode", "bilingual",
                "languages", List.of("zh", "en"),
                "modelMustGenerateBilingualRetrieval", true,
                "bilingualQueryFields", List.of("bilingualIntent", "bilingualQuery", "intentZh", "intentEn", "filters.bilingualIntent", "filters.intentZh", "filters.intentEn"),
                "queryLanguageHints", List.of("language", "queryLanguage", "filters.language", "filters.queryLanguage"),
                "intentExpansion", "Model-generated Chinese and English template intent terms are normalized into shared synonym sets before ranking"
            ),
            "routingProtocol", mapOf(
                "requiredMarker", "finalDecision",
                "legacyMarker", "targetKind",
                "preferredMarker", "finalDecision",
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION,
                "confidenceThreshold", TargetKindRegistry.MIN_CONFIDENCE,
                "candidateSet", mapOf(
                    "candidates", "candidates[]",
                    "finalDecision", "finalDecision",
                    "feasibilityLayer", List.of("schema_match", "tool_permission"),
                    "scoringLayer", List.of("confidence", "latency_estimate", "historical_success_rate")
                ),
                "allowedTargetKinds", List.of("host", "database", "http", "business_database_query"),
                "targetKindToAssetType", mapOf(
                    "host", "ssh_host",
                    "database", "sql_datasource",
                    "http", "http_endpoint",
                    "business_database_query", "database_query",
                    "document", "document_search"
                ),
                "doNotInferFromKeywords", true
            ),
            "forbiddenConcreteTargetFields", List.of(
                "hostId",
                "host",
                "hostname",
                "ip",
                "ipAddress",
                "address",
                "datasourceId",
                "jdbcUrl",
                "url",
                "endpointId",
                "command",
                "rawCommand",
                "shell",
                "sql",
                "rawSql",
                "body",
                "bodyTemplate"
            ),
            "rawExecutionSpecReturned", false
        );
    }

    private Map<String, Object> errorResult(String message) {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", message,
            "errorDetail", mapOf(
                "code", "TEMPLATE_QUERY_REJECTED",
                "message", message,
                "required_fields", List.of("candidates", "finalDecision", "filters", "trace"),
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            )
        );
    }

    private Map<String, Object> errorResult(TargetKindRegistry.TargetKindException ex) {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", ex.getMessage(),
            "errorDetail", ex.details()
        );
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Template discovery MCP tool {} was not registered: {}", toolName, ex.getMessage());
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
