package com.chatchat.mcpserver.routing;

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
public class AssetDiscoveryMcpToolPublisher {

    public static final String SSH_ASSET_TOOL_NAME = "ssh_asset_query";
    public static final String SQL_DATASOURCE_ASSET_TOOL_NAME = "database_asset_search";
    public static final String LEGACY_SQL_DATASOURCE_ASSET_TOOL_NAME = "sql_datasource_asset_query";
    public static final String HTTP_ENDPOINT_ASSET_TOOL_NAME = "http_endpoint_asset_query";

    private final McpSyncServer mcpSyncServer;
    private final AssetDiscoveryService assetDiscoveryService;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove(SSH_ASSET_TOOL_NAME);
        remove(SQL_DATASOURCE_ASSET_TOOL_NAME);
        remove(LEGACY_SQL_DATASOURCE_ASSET_TOOL_NAME);
        remove(HTTP_ENDPOINT_ASSET_TOOL_NAME);
        mcpSyncServer.addTool(assetQueryTool(
            SSH_ASSET_TOOL_NAME,
            "SSH asset metadata discovery",
            "Read-only discovery tool for querying redacted SSH host asset metadata and routing hints.",
            "ssh_host",
            "host"
        ));
        mcpSyncServer.addTool(assetQueryTool(
            SQL_DATASOURCE_ASSET_TOOL_NAME,
            "Database asset search",
            "Read-only discovery tool for confirming redacted database datasource assets and routing hints.",
            "sql_datasource",
            "database"
        ));
        mcpSyncServer.addTool(assetQueryTool(
            HTTP_ENDPOINT_ASSET_TOOL_NAME,
            "HTTP endpoint asset metadata discovery",
            "Read-only discovery tool for querying redacted HTTP endpoint asset metadata and routing hints.",
            "http_endpoint",
            "http"
        ));
        mcpSyncServer.notifyToolsListChanged();
        log.info("Asset discovery MCP tools refreshed: {}, {}, {}",
            SSH_ASSET_TOOL_NAME, SQL_DATASOURCE_ASSET_TOOL_NAME, HTTP_ENDPOINT_ASSET_TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification assetQueryTool(String toolName,
                                                                   String title,
                                                                   String description,
                                                                   String assetType,
                                                                   String targetKind) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(toolName)
            .title(title)
            .description(description + " "
                + "Prefer logical context filters when known; if none are known it returns redacted candidate assets. "
                + "It forces assetType=" + assetType + " and targetKind=" + targetKind
                + ", so model mistakes cannot route this request into another asset type. "
                + "It never returns hostnames, IP addresses, JDBC URLs, or endpoint URLs. The result returns a single canonical redacted assets[] view.")
            .inputSchema(inputSchema(assetType))
            .meta(meta(toolName, assetType, targetKind))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = assetDiscoveryService.query(forcedAssetArguments(
                        request.arguments(), toolName, assetType, targetKind));
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("Asset metadata query completed")
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

    private Map<String, Object> forcedAssetArguments(Map<String, Object> arguments,
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

    private McpSchema.JsonSchema inputSchema(String assetType) {
        return new McpSchema.JsonSchema("object", mapOf(
            "schemaVersion", Map.of("type", "string", "description", AssetDiscoveryService.QUERY_SCHEMA_VERSION),
            "filtersSchemaVersion", Map.of(
                "type", "string",
                "description", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            ),
            "filters", Map.of(
                "type", "object",
                "description", "Optional logical context filters for " + assetType + ", such as assetName, env, cluster, service, target, database, databaseRole, or labels. Asset names and labels are exact-match; no token splitting is applied. Omit or pass {} only when the user has not provided exact logical context.",
                "additionalProperties", true
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for filters; concrete target fields are forbidden",
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
                "description", "Optional maximum number of assets returned. Omit this field to return all matched assets."
            ),
            "view", Map.of(
                "type", "string",
                "description", "Optional response view: model or system. v1 always returns the canonical redacted assets[] representation."
            )
        ), List.of("filters", "trace"), false, null, null);
    }

    private Map<String, Object> meta(String toolName, String assetType, String targetKind) {
        return mapOf(
            "schemaVersion", AssetDiscoveryService.QUERY_SCHEMA_VERSION,
            "kind", "asset_discovery_tool",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "controlPlane", "discovery",
            "readOnly", true,
            "risk_level", "low",
            "riskLevel", "low",
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            "targetKind", targetKind,
            "assetType", assetType,
            "toolBoundary", mapOf(
                "toolName", toolName,
                "forcedAssetType", assetType,
                "forcedTargetKind", targetKind,
                "rejectCrossTypeRouting", true
            ),
            "requiresContextFilter", false,
            "matchPolicy", "exact_asset_name_or_explicit_label",
            "broadDiscovery", mapOf(
                "enabled", true,
                "maxResults", "unlimited unless request.limit is provided",
                "redactedCandidatesOnly", true,
                "useWhen", "no exact assetName/env/cluster/service/target/database label is known"
            ),
            "maxResults", "unlimited unless request.limit is provided",
            "routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION,
            "routingProtocol", mapOf(
                "forcedTargetKind", targetKind,
                "forcedAssetType", assetType,
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION,
                "doNotInferFromKeywords", true
            ),
            "indexPolicy", mapOf(
                "logicalIndex", "asset:" + assetType,
                "physicalIndex", assetPhysicalIndex(assetType),
                "indexBackend", "lucene_typed_asset_index",
                "filterField", "assetType",
                "isolatedByTool", true
            ),
            "resultShape", mapOf(
                "canonical", "assets[]",
                "assetEnvironmentPath", "assets[].asset.environment",
                "assetNamePath", "assets[].asset.name",
                "commandTemplatesPath", "assets[].capabilities.allowedCommandTemplates[].templateId",
                "commandTemplateIdsPath", "assets[].capabilities.allowedCommandTemplateIds[]",
                "sqlTemplatesPath", "assets[].capabilities.allowedQueryTemplates[].templateId",
                "sqlTemplateIdsPath", "assets[].capabilities.allowedQueryTemplateIds[]"
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
                "connectionString",
                "endpointId",
                "uri"
            )
        );
    }

    private String assetPhysicalIndex(String assetType) {
        return "assets-" + String.valueOf(assetType).replace('_', '-');
    }

    private Map<String, Object> errorResult(String message) {
        return mapOf(
            "schemaVersion", AssetDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", AssetDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", message,
            "errorDetail", mapOf(
                "code", "ASSET_QUERY_REJECTED",
                "message", message,
                "required_fields", List.of("candidates", "finalDecision", "filters", "trace"),
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            )
        );
    }

    private Map<String, Object> errorResult(TargetKindRegistry.TargetKindException ex) {
        return mapOf(
            "schemaVersion", AssetDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", AssetDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", ex.getMessage(),
            "errorDetail", ex.details()
        );
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Asset discovery MCP tool {} was not registered: {}", toolName, ex.getMessage());
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
