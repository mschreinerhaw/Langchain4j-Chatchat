package com.chatchat.mcpserver.ops;

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
            .description("Read-only discovery tool for querying registered SSH, SQL, and HTTP execution templates. "
                + "It returns template metadata, risk level, parameter schema and routing hints, but never returns raw commands, SQL, URLs, or bodies.")
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
            "schemaVersion", Map.of("type", "string", "description", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION),
            "assetType", Map.of(
                "type", "string",
                "description", "Template target asset type: ssh_host, sql_datasource, or http_endpoint."
            ),
            "filters", Map.of(
                "type", "object",
                "description", "Logical filters such as assetName, env, cluster, service, target, intent, category, or labels",
                "additionalProperties", true
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for logical filters; concrete target fields and raw commands are forbidden",
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
        ), List.of(), false, null, null);
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
                "templateIdPath", "templates[].templateId"
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
            "error", message
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
