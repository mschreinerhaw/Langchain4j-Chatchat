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

    public static final String SSH_TEMPLATE_TOOL_NAME = "ssh_template_query";
    public static final String SQL_DATASOURCE_TEMPLATE_TOOL_NAME = "sql_datasource_template_query";
    public static final String HTTP_ENDPOINT_TEMPLATE_TOOL_NAME = "http_endpoint_template_query";
    public static final String DATABASE_QUERY_TEMPLATE_TOOL_NAME = "database_query_template_query";

    private final McpSyncServer mcpSyncServer;
    private final CommandTemplateDiscoveryService templateDiscoveryService;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove(SSH_TEMPLATE_TOOL_NAME);
        remove(SQL_DATASOURCE_TEMPLATE_TOOL_NAME);
        remove(HTTP_ENDPOINT_TEMPLATE_TOOL_NAME);
        remove(DATABASE_QUERY_TEMPLATE_TOOL_NAME);
        mcpSyncServer.addTool(domainTemplateQueryTool(
            SSH_TEMPLATE_TOOL_NAME,
            "SSH command template discovery",
            "Read-only MCP tool for retrieving SSH host command templates only.",
            "ssh_host",
            "host",
            "host command templates"
        ));
        mcpSyncServer.addTool(domainTemplateQueryTool(
            SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
            "SQL datasource template discovery",
            "Read-only MCP tool for retrieving SQL datasource query templates only.",
            "sql_datasource",
            "database",
            "SQL datasource templates"
        ));
        mcpSyncServer.addTool(domainTemplateQueryTool(
            HTTP_ENDPOINT_TEMPLATE_TOOL_NAME,
            "HTTP endpoint template discovery",
            "Read-only MCP tool for retrieving HTTP endpoint request templates only.",
            "http_endpoint",
            "http",
            "HTTP endpoint templates"
        ));
        mcpSyncServer.addTool(databaseQueryTemplateQueryTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Template discovery MCP tools refreshed: {}, {}, {}, {}",
            SSH_TEMPLATE_TOOL_NAME, SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
            HTTP_ENDPOINT_TEMPLATE_TOOL_NAME, DATABASE_QUERY_TEMPLATE_TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification domainTemplateQueryTool(String toolName,
                                                                           String title,
                                                                           String description,
                                                                           String assetType,
                                                                           String targetKind,
                                                                           String domainLabel) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(toolName)
            .title(title)
            .description(description + " It forces assetType=" + assetType + " and targetKind=" + targetKind
                + ", so model mistakes cannot route this request into another asset type. It returns "
                + domainLabel + " metadata, ranking traces, parameter schema and routing hints, but never returns raw execution specs.")
            .inputSchema(domainTemplateInputSchema(domainLabel))
            .meta(domainTemplateMeta(toolName, assetType, targetKind, domainLabel))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = templateDiscoveryService.query(forcedTemplateArguments(
                        request.arguments(), toolName, assetType, targetKind));
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(domainLabel + " query completed")
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

    private McpServerFeatures.SyncToolSpecification databaseQueryTemplateQueryTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(DATABASE_QUERY_TEMPLATE_TOOL_NAME)
            .title("Business database query template discovery")
            .description("Read-only MCP tool for retrieving business database query templates only. "
                + "It forces assetType=database_query and finalDecision=business_database_query, "
                + "so clients can discover authorized business query templates without routing through generic SSH, SQL datasource, or HTTP template domains. "
                + "It returns template metadata, parameter schema, ranking traces, and routing hints, but never returns raw SQL.")
            .inputSchema(databaseQueryTemplateInputSchema())
            .meta(databaseQueryTemplateMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = templateDiscoveryService.query(databaseQueryTemplateArguments(request.arguments()));
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("Business database query template query completed")
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

    private Map<String, Object> databaseQueryTemplateArguments(Map<String, Object> arguments) {
        return forcedTemplateArguments(arguments, DATABASE_QUERY_TEMPLATE_TOOL_NAME, "database_query", "business_database_query");
    }

    private Map<String, Object> forcedTemplateArguments(Map<String, Object> arguments,
                                                       String sourceTool,
                                                       String assetType,
                                                       String targetKind) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (arguments != null) {
            values.putAll(arguments);
        }
        values.put("assetType", assetType);
        values.put("finalDecision", targetKind);
        values.putIfAbsent("confidence", 1.0);
        values.putIfAbsent("candidates", List.of(mapOf(
            "targetKind", targetKind,
            "confidence", 1.0
        )));
        values.putIfAbsent("trace", mapOf(
            "source", sourceTool,
            "forcedAssetType", assetType,
            "forcedTargetKind", targetKind
        ));
        return values;
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

    private McpSchema.JsonSchema domainTemplateInputSchema(String domainLabel) {
        return new McpSchema.JsonSchema("object", mapOf(
            "schemaVersion", Map.of("type", "string", "description", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION),
            "filtersSchemaVersion", Map.of("type", "string", "description", TargetKindRegistry.FILTERS_SCHEMA_VERSION),
            "filters", Map.of(
                "type", "object",
                "description", "Logical filters for " + domainLabel + ", such as assetName, env, cluster, service, intent, bilingualIntent, intentZh, intentEn, category, labels, databaseType, dbType, language, or queryLanguage. Concrete target fields and raw execution specs are forbidden.",
                "additionalProperties", true
            ),
            "bilingualIntent", Map.of(
                "type", "array",
                "description", "Model-generated bilingual retrieval terms. Include both Chinese and English phrases.",
                "items", Map.of("type", "string")
            ),
            "bilingualQuery", Map.of("type", "string", "description", "Alias for model-generated bilingual retrieval text."),
            "intentZh", Map.of("type", "string", "description", "Chinese retrieval phrase generated by the model for template matching."),
            "intentEn", Map.of("type", "string", "description", "English retrieval phrase generated by the model for template matching."),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for logical filters. Concrete target fields and raw execution specs are forbidden.",
                "additionalProperties", true
            ),
            "trace", Map.of(
                "type", "object",
                "description", "Replay trace such as plannerVersion, model, promptVersion, or taskId.",
                "additionalProperties", true
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", CommandTemplateDiscoveryService.MAX_LIMIT,
                "description", "Maximum number of templates returned; capped at 20."
            ),
            "view", Map.of("type", "string", "description", "Optional response view: model or system.")
        ), List.of("filters"), false, null, null);
    }

    private McpSchema.JsonSchema databaseQueryTemplateInputSchema() {
        return new McpSchema.JsonSchema("object", mapOf(
            "schemaVersion", Map.of("type", "string", "description", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION),
            "filtersSchemaVersion", Map.of(
                "type", "string",
                "description", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            ),
            "filters", Map.of(
                "type", "object",
                "description", "Logical filters for business database query templates, such as intent, bilingualIntent, intentZh, intentEn, category, databaseType, dbType, labels, language, or queryLanguage. Concrete datasource fields and raw SQL are forbidden.",
                "additionalProperties", true
            ),
            "bilingualIntent", Map.of(
                "type", "array",
                "description", "Model-generated bilingual retrieval terms. Include both Chinese and English phrases.",
                "items", Map.of("type", "string")
            ),
            "bilingualQuery", Map.of(
                "type", "string",
                "description", "Alias for model-generated bilingual retrieval text."
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
                "description", "Optional query language hint: zh, en, or auto."
            ),
            "queryLanguage", Map.of(
                "type", "string",
                "description", "Alias of language for model-generated template retrieval requests."
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for logical filters. Concrete datasource fields and raw SQL are forbidden.",
                "additionalProperties", true
            ),
            "trace", Map.of(
                "type", "object",
                "description", "Replay trace such as plannerVersion, model, promptVersion, or taskId.",
                "additionalProperties", true
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", CommandTemplateDiscoveryService.MAX_LIMIT,
                "description", "Maximum number of templates returned; capped at 20."
            ),
            "view", Map.of(
                "type", "string",
                "description", "Optional response view: model or system."
            )
        ), List.of("filters"), false, null, null);
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

    private Map<String, Object> domainTemplateMeta(String toolName,
                                                  String assetType,
                                                  String targetKind,
                                                  String domainLabel) {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "kind", "typed_template_discovery_tool",
            "domain", domainLabel,
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "controlPlane", "discovery",
            "readOnly", true,
            "risk_level", "low",
            "riskLevel", "low",
            "targetKind", targetKind,
            "assetType", assetType,
            "toolBoundary", mapOf(
                "toolName", toolName,
                "forcedAssetType", assetType,
                "forcedTargetKind", targetKind,
                "rejectCrossTypeRouting", true
            ),
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            "resultShape", mapOf(
                "canonical", "templates[]",
                "templateIdPath", "templates[].templateId",
                "queryIrPath", "queryIr",
                "decisionTracePath", "resolutionTrace"
            ),
            "indexPolicy", mapOf(
                "logicalIndex", "template:" + assetType,
                "filterField", "assetType",
                "isolatedByTool", true
            ),
            "languageSupport", mapOf(
                "mode", "bilingual",
                "languages", List.of("zh", "en"),
                "modelMustGenerateBilingualRetrieval", true,
                "bilingualQueryFields", List.of("bilingualIntent", "bilingualQuery", "intentZh", "intentEn", "filters.bilingualIntent", "filters.intentZh", "filters.intentEn")
            ),
            "routingProtocol", mapOf(
                "forcedTargetKind", targetKind,
                "forcedAssetType", assetType,
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            ),
            "rawExecutionSpecReturned", false
        );
    }

    private Map<String, Object> databaseQueryTemplateMeta() {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "kind", "business_database_query_template_discovery_tool",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "controlPlane", "discovery",
            "readOnly", true,
            "risk_level", "low",
            "riskLevel", "low",
            "targetKind", "business_database_query",
            "assetType", "database_query",
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
                "bilingualQueryFields", List.of("bilingualIntent", "bilingualQuery", "intentZh", "intentEn", "filters.bilingualIntent", "filters.intentZh", "filters.intentEn")
            ),
            "routingProtocol", mapOf(
                "forcedTargetKind", "business_database_query",
                "forcedAssetType", "database_query",
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            ),
            "forbiddenConcreteTargetFields", List.of(
                "datasourceId",
                "jdbcUrl",
                "connectionString",
                "sql",
                "rawSql"
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
