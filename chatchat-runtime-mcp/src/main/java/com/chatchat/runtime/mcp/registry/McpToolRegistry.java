package com.chatchat.runtime.mcp.registry;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.integration.mcp.service.McpCapabilityService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single registration point for built-in MCP tools. */
@Component
public class McpToolRegistry {
    private final ToolRegistry agentToolRegistry;
    private final McpCapabilityService capabilityService;
    private final McpCapabilitiesProperties properties;
    private final Map<String, RegisteredMcpTool> tools;

    public McpToolRegistry(List<McpToolProvider> providers, ToolRegistry agentToolRegistry,
                           McpCapabilityService capabilityService, McpCapabilitiesProperties properties) {
        this.agentToolRegistry = agentToolRegistry;
        this.capabilityService = capabilityService;
        this.properties = properties;
        Map<String, RegisteredMcpTool> discovered = new LinkedHashMap<>();
        for (McpToolProvider provider : providers) {
            for (McpToolDefinition definition : provider.definitions()) {
                validateProvider(provider, definition);
                McpToolExecutor executor = provider.findExecutor(definition.name())
                    .orElseThrow(() -> new IllegalStateException("MCP tool has no executor: " + definition.name()));
                RegisteredMcpTool registered = configured(definition, executor);
                if (discovered.putIfAbsent(definition.name(), registered) != null) {
                    throw new IllegalStateException("Duplicate MCP tool registration: " + definition.name());
                }
            }
        }
        this.tools = Collections.unmodifiableMap(discovered);
    }

    @PostConstruct
    public void publish() {
        tools.values().forEach(this::publishOne);
    }

    public Collection<RegisteredMcpTool> listTools() {
        return tools.values().stream().map(this::current).toList();
    }

    public RegisteredMcpTool require(String toolName) {
        RegisteredMcpTool tool = tools.get(toolName);
        if (tool == null) throw new IllegalArgumentException("MCP tool does not exist: " + toolName);
        return current(tool);
    }

    public boolean isActive(RegisteredMcpTool tool) {
        return tool.enabled() && capabilityEnabled(tool.definition().capabilityCode())
            && tool.runtimeStatus() != McpToolRuntimeStatus.DISABLED;
    }

    public synchronized void refreshCapability(String capabilityCode) {
        tools.values().stream()
            .filter(tool -> tool.definition().capabilityCode().equals(capabilityCode))
            .forEach(tool -> {
                agentToolRegistry.unregisterTool(tool.definition().name());
                publishOne(tool);
            });
    }

    private void publishOne(RegisteredMcpTool registered) {
        McpToolDefinition definition = registered.definition();
        if (!registered.enabled() || !capabilityEnabled(definition.capabilityCode())) return;
        ToolMetadata metadata = ToolMetadata.builder()
            .id(definition.name()).title(definition.displayName()).description(definition.description())
            .version("1.0.0").author("ChatChat MCP Registry")
            .categories(List.of("mcp", definition.capabilityCode())).category(definition.capabilityCode())
            .riskLevel("low").operationType("read").runtimeLevel("readonly")
            .userVisible(true).outputType("json").returnDirect(false)
            .agentCompatible(registered.agentCallable()).parameters(definition.parameters())
            .timeoutMillis(registered.timeout().toMillis())
            .metadata(Map.of(
                "mcpCapability", true,
                "mcpCapabilityCode", definition.capabilityCode(),
                "providerModule", definition.provider(),
                "runtimeStatus", registered.runtimeStatus().name()
            )).build();
        agentToolRegistry.registerTool(definition.name(), metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) { return registered.executor().execute(input); }
        });
    }

    private RegisteredMcpTool current(RegisteredMcpTool registered) {
        boolean active = registered.enabled() && capabilityEnabled(registered.definition().capabilityCode());
        McpToolRuntimeStatus status = active ? registered.executor().runtimeStatus() : McpToolRuntimeStatus.DISABLED;
        return new RegisteredMcpTool(registered.definition(), registered.executor(), registered.enabled(),
            registered.agentCallable(), registered.timeout(), status);
    }

    private RegisteredMcpTool configured(McpToolDefinition definition, McpToolExecutor executor) {
        McpCapabilitiesProperties.Tool config = properties.tool(definition.capabilityCode(), definition.name());
        boolean enabled = config == null || config.getEnabled() == null ? definition.enabledByDefault() : config.getEnabled();
        boolean callable = config == null || config.getAgentCallable() == null ? definition.agentCallable() : config.getAgentCallable();
        return new RegisteredMcpTool(definition, executor, enabled, callable, effectiveTimeout(definition),
            enabled ? executor.runtimeStatus() : McpToolRuntimeStatus.DISABLED);
    }

    private Duration effectiveTimeout(McpToolDefinition definition) {
        McpCapabilitiesProperties.Tool config = properties.tool(definition.capabilityCode(), definition.name());
        return config != null && config.getTimeout() != null ? config.getTimeout() : definition.timeout();
    }

    private boolean capabilityEnabled(String capabilityCode) {
        McpCapabilitiesProperties.Capability config = properties.getCapabilities().get(capabilityCode);
        if (config != null && Boolean.FALSE.equals(config.getEnabled())) return false;
        return capabilityService.findByCode(capabilityCode).map(capability -> capability.isEnabled()).orElse(true);
    }

    private void validateProvider(McpToolProvider provider, McpToolDefinition definition) {
        if (!provider.capabilityCode().equals(definition.capabilityCode())) {
            throw new IllegalStateException("MCP provider capability mismatch for tool: " + definition.name());
        }
    }
}
