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

    public static final String TOOL_NAME = "asset_query";

    private final McpSyncServer mcpSyncServer;
    private final AssetDiscoveryService assetDiscoveryService;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove(TOOL_NAME);
        mcpSyncServer.addTool(assetQueryTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Asset discovery MCP tool refreshed: {}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification assetQueryTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("Asset metadata discovery")
            .description("Read-only discovery tool for querying redacted asset metadata and routing hints. "
                + "Prefer logical context filters when known; if none are known it can return capped redacted candidate assets. "
                + "It never returns hostnames, IP addresses, JDBC URLs, or endpoint URLs. The result returns a single canonical redacted assets[] view.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = assetDiscoveryService.query(request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("Asset metadata query completed")
                        .structuredContent(result)
                        .isError(false)
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
        return new McpSchema.JsonSchema("object", Map.of(
            "schemaVersion", Map.of("type", "string", "description", AssetDiscoveryService.QUERY_SCHEMA_VERSION),
            "assetType", Map.of(
                "type", "string",
                "description", "Optional asset type: ssh_host, sql_datasource, or http_endpoint"
            ),
            "filters", Map.of(
                "type", "object",
                "description", "Optional logical context filters such as assetName, env, cluster, service, target, database, databaseRole, or labels. Asset names and labels are exact-match; no token splitting is applied. Omit or pass {} only when the user has not provided exact logical context.",
                "additionalProperties", true
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for filters; concrete target fields are forbidden",
                "additionalProperties", true
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", AssetDiscoveryService.MAX_LIMIT,
                "description", "Maximum number of assets returned; capped at 20"
            ),
            "view", Map.of(
                "type", "string",
                "description", "Optional response view: model or system. v1 always returns the canonical redacted assets[] representation."
            )
        ), List.of(), false, null, null);
    }

    private Map<String, Object> meta() {
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
            "requiresContextFilter", false,
            "matchPolicy", "exact_asset_name_or_explicit_label",
            "broadDiscovery", mapOf(
                "enabled", true,
                "maxResults", AssetDiscoveryService.MAX_LIMIT,
                "redactedCandidatesOnly", true,
                "useWhen", "no exact assetName/env/cluster/service/target/database label is known"
            ),
            "maxResults", AssetDiscoveryService.MAX_LIMIT,
            "routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION,
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

    private Map<String, Object> errorResult(String message) {
        return mapOf(
            "schemaVersion", AssetDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", AssetDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", message
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
