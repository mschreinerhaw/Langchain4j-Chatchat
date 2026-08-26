package com.chatchat.mcpserver.config;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.integration.mcp.service.McpCapabilityService;
import com.chatchat.runtime.mcp.registry.McpCapabilityStatePort;
import com.chatchat.runtime.mcp.registry.McpToolExecutor;
import com.chatchat.runtime.mcp.registry.McpToolPublicationPort;
import com.chatchat.runtime.mcp.registry.McpToolProvider;
import com.chatchat.runtime.mcp.registry.McpToolRegistry;
import com.chatchat.runtime.mcp.registry.McpToolInvocationService;
import com.chatchat.runtime.mcp.registry.McpCapabilitiesProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Host adapters keeping the MCP Runtime core independent of Agent and persistence implementations. */
@Configuration
public class McpRuntimeHostAdapters {

    @Bean
    McpToolRegistry mcpToolRegistry(java.util.List<McpToolProvider> providers,
                                    McpToolPublicationPort publicationPort,
                                    McpCapabilityStatePort capabilityStatePort,
                                    McpCapabilitiesProperties properties) {
        return new McpToolRegistry(providers, publicationPort, capabilityStatePort, properties);
    }

    @Bean
    McpToolInvocationService mcpToolInvocationService(McpToolRegistry registry) {
        return new McpToolInvocationService(registry);
    }

    @Bean
    McpToolPublicationPort mcpToolPublicationPort(ToolRegistry registry) {
        return new McpToolPublicationPort() {
            @Override
            public void publish(String toolName, ToolMetadata metadata, McpToolExecutor executor) {
                registry.registerTool(toolName, metadata, new ToolRegistry.EnhancedTool() {
                    @Override public ToolMetadata getMetadata() { return metadata; }
                    @Override public com.chatchat.common.tool.ToolOutput execute(
                        com.chatchat.common.tool.ToolInput input) { return executor.execute(input); }
                });
            }

            @Override public void unpublish(String toolName) { registry.unregisterTool(toolName); }
        };
    }

    @Bean
    McpCapabilityStatePort mcpCapabilityStatePort(McpCapabilityService capabilities) {
        return capabilityCode -> capabilities.findByCode(capabilityCode)
            .map(capability -> capability.isEnabled()).orElse(true);
    }
}
