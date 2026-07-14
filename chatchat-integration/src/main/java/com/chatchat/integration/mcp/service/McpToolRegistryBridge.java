package com.chatchat.integration.mcp.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronizes enabled MCP services into ToolRegistry as dynamic tools.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolRegistryBridge {

    private final ToolRegistry toolRegistry;
    private final McpServiceConfigService configService;
    private final McpGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;

    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();
    private final Map<String, RegisteredMcpTool> registeredTools = new ConcurrentHashMap<>();

    /**
     * Performs the initialize operation.
     */
    @PostConstruct
    public void initialize() {
        try {
            refreshRegistry();
        } catch (Exception ex) {
            log.warn("MCP tool registry initial refresh failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Performs the refresh registry operation.
     */
    public synchronized void refreshRegistry() {
        managedToolNames.forEach(toolRegistry::unregisterTool);
        managedToolNames.clear();
        registeredTools.clear();

        List<McpServiceConfig> services = configService.listEnabled();
        if (services.isEmpty()) {
            log.info("No enabled MCP service found, skip MCP tool registration");
            return;
        }

        for (McpServiceConfig service : services) {
            try {
                List<McpToolDefinition> tools = gatewayClient.discoverTools(service);
                if (tools.isEmpty()) {
                    log.info("No MCP tools discovered for service {}", service.getName());
                    continue;
                }
                for (McpToolDefinition definition : tools) {
                    registerSingleTool(service, definition);
                }
            } catch (Exception ex) {
                log.warn("Skip MCP service {} (id={}) during refresh: {}",
                    service.getName(), service.getId(), ex.getMessage());
            }
        }
        log.info("MCP tool registry refreshed, registered {} tools", managedToolNames.size());
    }

    /**
     * Lists the registered tools.
     *
     * @return the registered tools list
     */
    public List<RegisteredMcpTool> listRegisteredTools() {
        return registeredTools.values().stream()
            .sorted(Comparator.comparing(RegisteredMcpTool::localToolName))
            .toList();
    }

    /**
     * Performs the discover tools operation.
     *
     * @param serviceId the service id value
     * @return the operation result
     */
    public List<McpToolDefinition> discoverTools(String serviceId) {
        McpServiceConfig config = configService.getById(serviceId);
        return gatewayClient.discoverTools(config);
    }

    /**
     * Performs the invoke operation.
     *
     * @param serviceId the service id value
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @return the operation result
     */
    public McpToolInvokeResult invoke(String serviceId, String toolName, Map<String, Object> arguments) {
        McpServiceConfig config = configService.getById(serviceId);
        return gatewayClient.invokeTool(config, toolName, arguments);
    }

    /**
     * Registers the single tool.
     *
     * @param service the service value
     * @param definition the definition value
     */
    private void registerSingleTool(McpServiceConfig service, McpToolDefinition definition) {
        String localName = toLocalToolName(service.getName(), definition.name());
        String candidate = localName;
        int suffix = 2;
        while (toolRegistry.hasTool(candidate)) {
            candidate = localName + "_" + suffix;
            suffix += 1;
        }
        localName = candidate;

        Map<String, Object> extraMetadata = new LinkedHashMap<>();
        extraMetadata.put("serviceId", service.getId());
        extraMetadata.put("remoteToolName", definition.name());
        extraMetadata.put("inputSchema", definition.inputSchema() == null ? Map.of() : definition.inputSchema());
        if (definition.meta() != null && !definition.meta().isEmpty()) {
            extraMetadata.put("mcpToolMeta", definition.meta());
            copyToolResultInstruction(extraMetadata, definition.meta());
        }
        if (definition.timeoutMillis() != null) {
            extraMetadata.put("remoteTimeoutMs", definition.timeoutMillis());
        }

        String category = firstText(definition.category(), "mcp_external");
        List<String> categories = distinctStrings(List.of("mcp", "external", category));
        List<String> tags = new ArrayList<>(List.of("mcp", sanitize(service.getName())));
        tags.addAll(stringList(definition.meta() == null ? null : definition.meta().get("tags")));
        tags = distinctStrings(tags);
        Map<String, Object> applicability = applicability(definition);

        ToolMetadata metadata = ToolMetadata.builder()
            .id(localName)
            .title(definition.name())
            .description(definition.description())
            .version("1.0.0")
            .author("MCP:" + service.getName())
            .categories(categories)
            .category(category)
            .riskLevel(firstText(definition.riskLevel(), "medium"))
            .operationType(firstText(definition.operationType(), "read"))
            .runtimeLevel(definition.runtimeLevel())
            .userVisible(definition.userVisible() == null || definition.userVisible())
            .confirmation(emptyToNull(definition.confirmation()))
            .permissions(emptyToNull(definition.permissions()))
            .inputPolicy(emptyToNull(definition.inputPolicy()))
            .outputPolicy(emptyToNull(definition.outputPolicy()))
            .outputType("json")
            .timeoutMillis(null)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Natural language query for MCP tool input")
                    .required(false)
                    .build()
            ))
            .tags(tags)
            .metadata(extraMetadata)
            .build();

        ToolRegistry.EnhancedTool tool = new McpEnhancedTool(service.getId(), definition.name(), metadata);
        toolRegistry.registerTool(localName, metadata, tool);
        managedToolNames.add(localName);
        registeredTools.put(localName, new RegisteredMcpTool(
            localName,
            service.getId(),
            service.getName(),
            definition.name(),
            definition.description(),
            backendServiceType(definition),
            category,
            categories,
            tags,
            applicability
        ));
    }

    private Map<String, Object> applicability(McpToolDefinition definition) {
        if (definition == null || definition.meta() == null) {
            return Map.of();
        }
        Object value = definition.meta().get("applicability");
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return Map.copyOf(result);
    }

    /**
     * Reads the backend service type declared by the MCP tool itself. No tool-name inference is used.
     */
    private String backendServiceType(McpToolDefinition definition) {
        if (definition == null || definition.meta() == null || definition.meta().isEmpty()) {
            return null;
        }
        Map<String, Object> meta = definition.meta();
        Map<String, Object> applicability = applicability(definition);
        List<String> declaredTypes = stringList(applicability.get("backendServiceTypes"));
        Object declared = firstPresent(
            declaredTypes.isEmpty() ? null : declaredTypes.get(0),
            meta.get("backendServiceType"),
            meta.get("backend_service_type"),
            meta.get("serviceType"),
            meta.get("service_type"),
            meta.get("resourceType"),
            meta.get("resource_type"),
            meta.get("assetType"),
            meta.get("asset_type")
        );
        return declared == null || String.valueOf(declared).isBlank()
            ? null
            : String.valueOf(declared).trim();
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (!String.valueOf(value).isBlank()) {
            result.add(String.valueOf(value).trim());
        }
        return List.copyOf(result);
    }

    private List<String> distinctStrings(Iterable<String> values) {
        Set<String> distinct = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    distinct.add(value.trim());
                }
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * Converts the value to local tool name.
     *
     * @param serviceName the service name value
     * @param toolName the tool name value
     * @return the converted local tool name
     */
    private String toLocalToolName(String serviceName, String toolName) {
        return "mcp_" + sanitize(serviceName) + "_" + sanitize(toolName);
    }

    /**
     * Performs the sanitize operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "tool";
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "tool" : normalized;
    }

    /**
     * Performs the first text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Performs the empty to null operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private Map<String, Object> emptyToNull(Map<String, Object> value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private void copyToolResultInstruction(Map<String, Object> target, Map<String, Object> meta) {
        if (target == null || meta == null || meta.isEmpty()) {
            return;
        }
        Object instruction = firstPresent(
            meta.get("toolResultInstruction"),
            meta.get("tool_result_instruction"),
            meta.get("resultInstruction"),
            meta.get("result_instruction")
        );
        if (instruction != null && !String.valueOf(instruction).isBlank()) {
            target.put("toolResultInstruction", instruction);
        }
        Object resultSchema = firstPresent(meta.get("resultSchema"), meta.get("result_schema"), meta.get("outputSchema"));
        if (resultSchema != null) {
            target.put("toolResultSchema", resultSchema);
        }
    }

    private class McpEnhancedTool implements ToolRegistry.EnhancedTool {

        private final String serviceId;
        private final String remoteToolName;
        private final ToolMetadata metadata;

        /**
         * Creates a new McpToolRegistryBridge instance.
         *
         * @param serviceId the service id value
         * @param remoteToolName the remote tool name value
         * @param metadata the metadata value
         */
        private McpEnhancedTool(String serviceId, String remoteToolName, ToolMetadata metadata) {
            this.serviceId = serviceId;
            this.remoteToolName = remoteToolName;
            this.metadata = metadata;
        }

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return metadata;
        }

        /**
         * Executes the execute.
         *
         * @param input the input value
         * @return the operation result
         */
        @Override
        public ToolOutput execute(ToolInput input) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (input.getParameters() != null) {
                arguments.putAll(input.getParameters());
            }
            if (arguments.isEmpty() && input.getRawInput() != null && !input.getRawInput().isBlank()) {
                arguments.put("query", input.getRawInput());
            }
            enrichInvocationContext(arguments, input);
            long startedAt = System.currentTimeMillis();
            log.info("MCP bridge tool call started localTool={} serviceId={} remoteTool={} requestId={} timeoutMs={} args={}",
                metadata.getId(),
                serviceId,
                remoteToolName,
                input.getRequestId(),
                "unbounded",
                ToolLogSummarizer.summarize(arguments));
            McpToolInvokeResult result = gatewayClient.invokeTool(
                configService.getById(serviceId),
                remoteToolName,
                arguments,
                metadata.getTimeoutMillis()
            );
            if (!result.success()) {
                log.warn("MCP bridge tool call failed localTool={} serviceId={} remoteTool={} requestId={} durationMs={} errorCode={} action={} retryable={} error={} executionState={} result={}",
                    metadata.getId(),
                    serviceId,
                    remoteToolName,
                    input.getRequestId(),
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    result.errorCode(),
                    result.action(),
                    result.retryable(),
                    result.errorMessage(),
                    result.executionState(),
                    ToolLogSummarizer.summarize(result.data()));
                return failureOutput(result);
            }
            log.info("MCP bridge tool call succeeded localTool={} serviceId={} remoteTool={} requestId={} durationMs={} message={} result={}",
                metadata.getId(),
                serviceId,
                remoteToolName,
                input.getRequestId(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                result.message(),
                ToolLogSummarizer.summarize(result.data()));
            ToolOutput output = ToolOutput.success(result.data(), result.message() == null ? "MCP call success" : result.message());
            if (output.getMetadata() == null) {
                output.setMetadata(new LinkedHashMap<>());
            }
            copyToolResultInstruction(output.getMetadata(), metadata.getMetadata());
            enrichOutputMetadata(output, result);
            return output;
        }
    }

    private ToolOutput failureOutput(McpToolInvokeResult result) {
        String errorMessage = result == null || result.errorMessage() == null
            ? "MCP tool call failed"
            : result.errorMessage();
        ToolOutput output = ToolOutput.failure(errorMessage);
        String errorCode = result == null ? null : result.errorCode();
        output.setExceptionType(firstText(errorCode, "MCP_TOOL_CALL_FAILED"));
        enrichOutputMetadata(output, result);
        output.getMetadata().put("errorCode", firstText(errorCode, "MCP_TOOL_CALL_FAILED"));
        output.getMetadata().put("retryable", result != null && result.retryable());
        output.getMetadata().put("action", result == null ? "STOP" : firstText(result.action(), "STOP"));
        return output;
    }

    private void enrichOutputMetadata(ToolOutput output, McpToolInvokeResult result) {
        if (output == null) {
            return;
        }
        if (output.getMetadata() == null) {
            output.setMetadata(new LinkedHashMap<>());
        }
        if (result == null) {
            return;
        }
        if (result.executionState() != null && !result.executionState().isEmpty()) {
            output.getMetadata().put("executionState", result.executionState());
            output.getMetadata().put("executionStateName", result.executionState().get("state"));
            output.getMetadata().put("executionRetryCount", result.executionState().get("retryCount"));
        }
        output.getMetadata().put("mcpAction", result.action());
        output.getMetadata().put("mcpRetryable", result.retryable());
    }

    @SuppressWarnings("unchecked")
    private void enrichInvocationContext(Map<String, Object> arguments, ToolInput input) {
        if (arguments == null) {
            return;
        }
        Map<String, Object> inputContext = input == null || input.getContext() == null ? Map.of() : input.getContext();
        String tenantId = firstText(
            stringValue(arguments.get("tenantId")),
            stringValue(arguments.get("tenant_id")),
            stringValue(inputContext.get("tenantId")),
            stringValue(inputContext.get("tenant_id")),
            stringValue(inputContext.get("tenant"))
        );
        String userId = firstText(
            stringValue(arguments.get("userId")),
            stringValue(arguments.get("user_id")),
            stringValue(inputContext.get("userId")),
            stringValue(inputContext.get("user_id")),
            input == null ? null : input.getUserId(),
            "anonymous"
        );
        String requestId = firstText(
            stringValue(arguments.get("requestId")),
            stringValue(arguments.get("request_id")),
            stringValue(inputContext.get("requestId")),
            input == null ? null : input.getRequestId()
        );
        String conversationId = firstText(
            stringValue(arguments.get("conversationId")),
            stringValue(arguments.get("conversation_id")),
            stringValue(inputContext.get("conversationId")),
            input == null ? null : input.getConversationId()
        );

        if (tenantId != null) {
            arguments.putIfAbsent("tenantId", tenantId);
        }
        arguments.putIfAbsent("userId", userId);
        if (requestId != null) {
            arguments.putIfAbsent("requestId", requestId);
        }
        if (conversationId != null) {
            arguments.putIfAbsent("conversationId", conversationId);
        }
        copyContextMap(arguments, inputContext, "defaultDataAsset");
        copyContextMap(arguments, inputContext, "assetSelectionPolicy");
        copyContextMap(arguments, inputContext, "mcpExecutionContext");

        Map<String, Object> mcpContext = arguments.get("mcpContext") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Map<String, Object> tenant = mcpContext.get("tenant") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        if (tenantId != null) {
            tenant.putIfAbsent("tenantId", tenantId);
            mcpContext.put("tenant", tenant);
            mcpContext.putIfAbsent("tenantId", tenantId);
        }
        mcpContext.putIfAbsent("userId", userId);
        if (requestId != null) {
            mcpContext.putIfAbsent("traceId", requestId);
        }
        if (conversationId != null) {
            mcpContext.putIfAbsent("conversationId", conversationId);
        }
        arguments.put("mcpContext", mcpContext);
    }

    @SuppressWarnings("unchecked")
    private void copyContextMap(Map<String, Object> arguments, Map<String, Object> inputContext, String key) {
        if (arguments == null || inputContext == null || key == null || key.isBlank() || arguments.containsKey(key)) {
            return;
        }
        Object value = inputContext.get(key);
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            arguments.put(key, new LinkedHashMap<>((Map<String, Object>) map));
        }
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record RegisteredMcpTool(
        String localToolName,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String description,
        String backendServiceType,
        String category,
        List<String> categories,
        List<String> tags,
        Map<String, Object> applicability
    ) {
        public RegisteredMcpTool(String localToolName,
                                 String serviceId,
                                 String serviceName,
                                 String remoteToolName,
                                 String description) {
            this(localToolName, serviceId, serviceName, remoteToolName, description, null,
                null, List.of(), List.of(), Map.of());
        }

        public RegisteredMcpTool(String localToolName,
                                 String serviceId,
                                 String serviceName,
                                 String remoteToolName,
                                 String description,
                                 String backendServiceType) {
            this(localToolName, serviceId, serviceName, remoteToolName, description, backendServiceType,
                null, List.of(), List.of(), Map.of());
        }

        public RegisteredMcpTool {
            categories = categories == null ? List.of() : List.copyOf(categories);
            tags = tags == null ? List.of() : List.copyOf(tags);
            applicability = applicability == null ? Map.of() : Map.copyOf(applicability);
        }
    }

    /**
     * Performs the serialize object operation.
     *
     * @param object the object value
     * @return the operation result
     */
    public String serializeObject(Object object) {
        if (object == null) {
            return "";
        }
        if (object instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            return String.valueOf(object);
        }
    }

    /**
     * Performs the argument keys operation.
     *
     * @param arguments the arguments value
     * @return the operation result
     */
    private List<String> argumentKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted()
            .toList();
    }
}
