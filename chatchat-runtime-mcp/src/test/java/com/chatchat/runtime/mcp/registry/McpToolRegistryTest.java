package com.chatchat.runtime.mcp.registry;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.integration.mcp.service.McpCapabilityService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolRegistryTest {
    @Test
    void discoversAndPublishesProviderToolsOnce() {
        ToolRegistry agentRegistry = mock(ToolRegistry.class);
        McpCapabilityService capabilityService = mock(McpCapabilityService.class);
        when(capabilityService.findByCode("news")).thenReturn(Optional.empty());
        McpToolRegistry registry = new McpToolRegistry(List.of(provider("web_search")), agentRegistry,
            capabilityService, new McpCapabilitiesProperties());

        registry.publish();

        assertThat(registry.listTools()).extracting(tool -> tool.definition().name()).containsExactly("web_search");
        verify(agentRegistry).registerTool(anyString(), any(), any());
    }

    @Test
    void rejectsDuplicateNamesAcrossProviders() {
        assertThatThrownBy(() -> new McpToolRegistry(List.of(provider("web_search"), provider("web_search")),
            mock(ToolRegistry.class), mock(McpCapabilityService.class), new McpCapabilitiesProperties()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate MCP tool");
    }

    @Test
    void disabledToolIsNotPublished() {
        McpCapabilitiesProperties properties = new McpCapabilitiesProperties();
        McpCapabilitiesProperties.Capability capability = new McpCapabilitiesProperties.Capability();
        McpCapabilitiesProperties.Tool tool = new McpCapabilitiesProperties.Tool();
        tool.setEnabled(false);
        capability.setTools(java.util.Map.of("web_search", tool));
        properties.setCapabilities(java.util.Map.of("news", capability));
        ToolRegistry agentRegistry = mock(ToolRegistry.class);
        McpToolRegistry registry = new McpToolRegistry(List.of(provider("web_search")), agentRegistry,
            mock(McpCapabilityService.class), properties);

        registry.publish();

        verify(agentRegistry, never()).registerTool(anyString(), any(), any());
    }

    private McpToolProvider provider(String name) {
        McpToolDefinition definition = new McpToolDefinition(name, name, "description", "news", "test-provider",
            List.of(), true, true, Duration.ofSeconds(10));
        McpToolExecutor executor = input -> ToolOutput.success("ok");
        return new McpToolProvider() {
            @Override public String capabilityCode() { return "news"; }
            @Override public Collection<McpToolDefinition> definitions() { return List.of(definition); }
            @Override public Optional<McpToolExecutor> findExecutor(String toolName) { return Optional.of(executor); }
        };
    }
}
