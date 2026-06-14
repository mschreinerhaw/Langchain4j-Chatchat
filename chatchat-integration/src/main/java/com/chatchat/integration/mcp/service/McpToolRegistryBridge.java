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
        if (definition.timeoutMillis() != null) {
            extraMetadata.put("remoteTimeoutMs", definition.timeoutMillis());
        }

        ToolMetadata metadata = ToolMetadata.builder()
            .id(localName)
            .title(definition.name())
            .description(definition.description())
            .version("1.0.0")
            .author("MCP:" + service.getName())
            .categories(List.of("mcp", "external"))
            .category(firstText(definition.category(), "mcp_external"))
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
            .tags(List.of("mcp", sanitize(service.getName())))
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
            definition.description()
        ));
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
                log.warn("MCP bridge tool call failed localTool={} serviceId={} remoteTool={} requestId={} durationMs={} error={} result={}",
                    metadata.getId(),
                    serviceId,
                    remoteToolName,
                    input.getRequestId(),
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    result.errorMessage(),
                    ToolLogSummarizer.summarize(result.data()));
                return ToolOutput.failure(result.errorMessage() == null ? "MCP tool call failed" : result.errorMessage());
            }
            log.info("MCP bridge tool call succeeded localTool={} serviceId={} remoteTool={} requestId={} durationMs={} message={} result={}",
                metadata.getId(),
                serviceId,
                remoteToolName,
                input.getRequestId(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                result.message(),
                ToolLogSummarizer.summarize(result.data()));
            return ToolOutput.success(result.data(), result.message() == null ? "MCP call success" : result.message());
        }
    }

    public record RegisteredMcpTool(
        String localToolName,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String description
    ) {
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
