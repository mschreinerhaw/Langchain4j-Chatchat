package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpsMcpToolPublisher {

    private final McpSyncServer mcpSyncServer;
    private final SshHostConfigService hostConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final HttpRequestToolService httpRequestToolService;
    private final LinuxCommandService linuxCommandService;
    private final ExecutionTargetRouter executionTargetRouter;
    private final AssetMetadataFactory assetMetadataFactory;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final StandardToolExecutionResultFactory standardResultFactory;
    private final ObjectMapper objectMapper;
    private final Set<String> managedSshToolNames = ConcurrentHashMap.newKeySet();
    private final Set<String> managedHttpToolNames = ConcurrentHashMap.newKeySet();

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove("linux_command_execute");
        remove("http_request_execute");
        httpEndpointConfigService.listAll().forEach(endpoint -> remove(endpoint.getToolName()));
        hostConfigService.listAll().forEach(host -> remove(host.getToolName()));
        managedHttpToolNames.forEach(this::remove);
        managedHttpToolNames.clear();
        managedSshToolNames.forEach(this::remove);
        managedSshToolNames.clear();

        mcpSyncServer.addTool(linuxCommandGatewayTool());
        mcpSyncServer.addTool(httpRequestGatewayTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Ops MCP gateway tools refreshed: linux_command_execute, http_request_execute");
    }

    private McpServerFeatures.SyncToolSpecification httpEndpointTool(HttpEndpointConfig endpoint) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(endpoint.getToolName())
            .title(endpoint.getTitle())
            .description(description(endpoint))
            .inputSchema(inputSchema(endpoint))
            .meta(httpEndpointMeta(endpoint))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                endpoint.getToolName(),
                "http",
                request.arguments(),
                () -> toCallToolResult(httpRequestToolService.execute(endpoint, request.arguments()))))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification linuxCommandGatewayTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("linux_command_execute")
            .title("Linux command execution gateway")
            .description("Execute a runtime-registered Linux command template on a routed logical host target. "
                + "Do not pass hostId, hostname, IP address, or any concrete machine identifier.")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "template", Map.of("type", "string", "description", "Command template id, for example CHECK_CPU, CHECK_DISK, CHECK_SERVICE_STATUS"),
                "parameters", Map.of("type", "object", "additionalProperties", true),
                "executionContext", Map.of(
                    "type", "object",
                    "description", "Logical target context such as env, cluster, targetType, target, service, hostSelector, or labels"
                ),
                "reason", Map.of("type", "string", "description", "Execution reason for user confirmation and audit"),
                "sourceTaskId", Map.of("type", "string")
            ), List.of("template", "executionContext"), false, null, null))
            .meta(linuxCommandGatewayMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                "linux_command_execute",
                "ssh",
                request.arguments(),
                () -> toCallToolResult(linuxCommandService.execute(
                    executionTargetRouter.routeLinuxCommand(request.arguments())))))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification httpRequestGatewayTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("http_request_execute")
            .title("HTTP request execution gateway")
            .description("Execute a configured HTTP endpoint through logical target routing. "
                + "Do not pass endpointId, URL, host, IP address, or any concrete endpoint identifier.")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "executionContext", Map.of(
                    "type", "object",
                    "description", "Logical endpoint context such as env, cluster, targetType, target, service, or labels"
                ),
                "template", Map.of("type", "string", "description", "HTTP template id returned by template_query; this is a logical selector, not a URL"),
                "parameters", Map.of("type", "object", "additionalProperties", true),
                "sourceTaskId", Map.of("type", "string"),
                "reason", Map.of("type", "string", "description", "Execution reason for confirmation and audit")
            ), List.of("executionContext"), true, null, null))
            .meta(httpRequestGatewayMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                "http_request_execute",
                "http",
                request.arguments(),
                () -> {
                    ExecutionTargetRouter.RoutedHttpEndpoint routed = executionTargetRouter.routeHttpRequest(request.arguments());
                    return toCallToolResult(httpRequestToolService.execute(
                        routed.endpoint(),
                        httpGatewayArguments(routed.arguments())));
                }))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification sshTool(SshHostConfig host) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(host.getToolName())
            .title(host.getTitle())
            .description(description(host))
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "template", Map.of("type", "string", "description", "Command template id, for example CHECK_CPU, CHECK_DISK, CHECK_SERVICE_STATUS"),
                "parameters", Map.of("type", "object", "additionalProperties", true),
                "reason", Map.of("type", "string", "description", "Execution reason for user confirmation and audit"),
                "sourceTaskId", Map.of("type", "string")
            ), List.of("template"), false, null, null))
            .meta(sshMeta(host))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                host.getToolName(),
                "ssh",
                request.arguments(),
                () -> toCallToolResult(linuxCommandService.execute(host, request.arguments()))))
            .build();
    }


    private Map<String, Object> httpEndpointMeta(HttpEndpointConfig endpoint) {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "http_asset");
        governance.put("operation_type", "request");
        governance.put("risk_level", "low");
        governance.put("data_scope", "http:" + endpoint.getName());
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "auto_execute", "allow_user_override", true));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("http_asset", endpoint.getId(), governance, endpoint.getGovernanceJson()));
        meta.put("runtime_action", endpoint.getRuntimeAction());
        meta.put("runtimeAction", endpoint.getRuntimeAction());
        meta.put("endpointId", endpoint.getId());
        meta.put("endpointName", endpoint.getName());
        meta.put("environment", endpoint.getEnvironment());
        meta.put("assetCategory", endpoint.getCategory());
        meta.put("endpointCategory", endpoint.getCategory());
        meta.put("method", endpoint.getMethod());
        meta.put("assetMetadata", assetMetadataFactory.httpEndpoint(endpoint));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(endpoint.getToolName(), "http"));
        return meta;
    }

    private Map<String, Object> sshMeta(SshHostConfig host) {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "host_asset");
        governance.put("operation_type", "execute_template");
        governance.put("risk_level", "high");
        governance.put("data_scope", "host:" + host.getName());
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "ask_before_execute", "allow_user_override", false));
        governance.put("input_policy", mutableMap(
            "must_show_parameters", true,
            "required_preview_params", List.of("template", "parameters", "reason", "sourceTaskId")
        ));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("host_asset", host.getId(), governance, host.getGovernanceJson()));
        meta.put("runtime_action", host.getRuntimeAction());
        meta.put("runtimeAction", host.getRuntimeAction());
        meta.put("hostId", host.getId());
        meta.put("hostName", host.getName());
        meta.put("environment", host.getEnvironment());
        meta.put("templateRegistryRequired", true);
        meta.put("allowedCommands", allowedCommands(host));
        meta.put("assetMetadata", assetMetadataFactory.sshAsset(host));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(host.getToolName(), "ssh"));
        return meta;
    }

    private Map<String, Object> linuxCommandGatewayMeta() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "host_gateway");
        governance.put("operation_type", "execute_template");
        governance.put("risk_level", "high");
        governance.put("data_scope", "host:routed");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "ask_before_execute", "allow_user_override", false));
        governance.put("input_policy", mutableMap(
            "must_show_parameters", true,
            "required_preview_params", List.of("template", "parameters", "executionContext", "reason", "sourceTaskId")
        ));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("host_gateway", "linux_command_execute", governance, null));
        meta.put("runtime_action", "confirm_required");
        meta.put("runtimeAction", "confirm_required");
        meta.put("templateRegistryRequired", true);
        meta.put("targetRoutingRequired", true);
        meta.put("forbiddenTargetFields", List.of("hostId", "host", "hostname", "ip", "ipAddress", "address"));
        meta.put("assetMetadata", assetMetadataFactory.gateway(
            "ssh_host",
            hostConfigService.listEnabled().stream().map(assetMetadataFactory::sshAsset).toList(),
            List.of("hostId", "host", "hostname", "ip", "ipAddress", "address")
        ));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta("linux_command_execute", "ssh"));
        return meta;
    }

    private Map<String, Object> httpRequestGatewayMeta() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "http_gateway");
        governance.put("operation_type", "request");
        governance.put("risk_level", "high");
        governance.put("data_scope", "http:routed");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "ask_before_execute", "allow_user_override", false));
        governance.put("input_policy", mutableMap(
            "must_show_parameters", true,
            "required_preview_params", List.of("template", "executionContext", "parameters", "reason", "sourceTaskId")
        ));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("http_gateway", "http_request_execute", governance, null));
        meta.put("runtime_action", "confirm_required");
        meta.put("runtimeAction", "confirm_required");
        meta.put("targetRoutingRequired", true);
        meta.put("forbiddenTargetFields", List.of("endpointId", "url", "uri", "host", "hostname", "ip", "ipAddress", "address"));
        meta.put("assetMetadata", assetMetadataFactory.gateway(
            "http_endpoint",
            httpEndpointConfigService.listEnabled().stream().map(assetMetadataFactory::httpEndpoint).toList(),
            List.of("endpointId", "url", "uri", "host", "hostname", "ip", "ipAddress", "address")
        ));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta("http_request_execute", "http"));
        return meta;
    }

    private String description(SshHostConfig host) {
        if (host.getDescription() != null && !host.getDescription().isBlank()) {
            return host.getDescription();
        }
        return "Use this tool to inspect host " + host.getName()
            + " through SSH. Only runtime-registered command templates are allowed; free-form shell commands are forbidden.";
    }

    private String description(HttpEndpointConfig endpoint) {
        if (endpoint.getDescription() != null && !endpoint.getDescription().isBlank()) {
            return endpoint.getDescription();
        }
        return "Use this tool to call the configured HTTP endpoint " + endpoint.getName() + ".";
    }

    @SuppressWarnings("unchecked")
    private McpSchema.JsonSchema inputSchema(HttpEndpointConfig endpoint) {
        if (endpoint.getInputSchemaJson() == null || endpoint.getInputSchemaJson().isBlank()) {
            return new McpSchema.JsonSchema("object", Map.of(
                "sourceTaskId", Map.of("type", "string")
            ), List.of(), true, null, null);
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(endpoint.getInputSchemaJson(), new TypeReference<>() {});
            Object properties = schema.get("properties");
            Map<String, Object> propertyMap = properties instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            Object required = schema.get("required");
            List<String> requiredList = required instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
            Object additional = schema.get("additionalProperties");
            boolean additionalProperties = !(additional instanceof Boolean bool) || bool;
            return new McpSchema.JsonSchema("object", propertyMap, requiredList, additionalProperties, null, null);
        } catch (Exception ex) {
            return new McpSchema.JsonSchema("object", Map.of(
                "sourceTaskId", Map.of("type", "string")
            ), List.of(), true, null, null);
        }
    }

    private McpSchema.CallToolResult toCallToolResult(Object result) {
        boolean success = true;
        Object structured = objectMapper.convertValue(result, Map.class);
        if (result instanceof HttpRequestToolResult http) {
            success = http.success();
            structured = standardResultFactory.fromHttp(http);
        } else if (result instanceof LinuxCommandResult linux) {
            success = linux.success();
            structured = standardResultFactory.fromLinuxCommand(linux);
        }
        return McpSchema.CallToolResult.builder()
            .addTextContent(success ? "Operation completed" : "Operation failed")
            .structuredContent(structured)
            .isError(!success)
            .build();
    }

    private Object allowedCommands(SshHostConfig host) {
        String json = host.getAllowedCommandsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return json;
        }
    }

    private Map<String, Object> mutableMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> httpGatewayArguments(Map<String, Object> arguments) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Object parameters = values.remove("parameters");
        if (parameters instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach(values::putIfAbsent);
        }
        values.remove("template");
        values.remove("templateId");
        values.remove("template_id");
        values.remove("reason");
        return values;
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Ops MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }
}
