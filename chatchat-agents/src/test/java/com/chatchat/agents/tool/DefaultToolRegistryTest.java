package com.chatchat.agents.tool;

import com.chatchat.common.tool.ToolMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class DefaultToolRegistryTest {

    @Test
    void nullOrBlankLookupNeverLeaksConcurrentHashMapNullKeyFailure() {
        DefaultToolRegistry registry = new DefaultToolRegistry();

        assertThat(registry.getTool(null)).isNull();
        assertThat(registry.getEnhancedTool(null)).isNull();
        assertThat(registry.getToolMetadata(null)).isNull();
        assertThat(registry.hasTool(null)).isFalse();
        assertThat(registry.getToolMetadata("  ")).isNull();
        assertThatCode(() -> registry.unregisterTool(null)).doesNotThrowAnyException();
    }

    @Test
    void unrelatedRegistrationDoesNotChangeAnExistingToolRevision() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolRegistry.Tool first = mock(ToolRegistry.Tool.class);
        ToolRegistry.Tool second = mock(ToolRegistry.Tool.class);
        registry.registerTool("first", first);
        long firstRevision = registry.getToolRevision("first");

        registry.registerTool("second", second);

        assertThat(registry.getRevision()).isGreaterThan(firstRevision);
        assertThat(registry.getToolRevision("first")).isEqualTo(firstRevision);
    }

    @Test
    void databaseFieldPolicyChangeUpdatesToolRevision() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolRegistry.EnhancedTool tool = mock(ToolRegistry.EnhancedTool.class);
        registry.registerTool("policy_tool", ToolMetadata.builder().id("policy_tool")
            .metadata(Map.of("workflowContractChecksum", "unchanged",
                "argumentBindingPolicy", Map.of("logicalContextKeys", List.of("region"))))
            .build(), tool);
        long firstRevision = registry.getToolRevision("policy_tool");

        registry.registerTool("policy_tool", ToolMetadata.builder().id("policy_tool")
            .metadata(Map.of("workflowContractChecksum", "unchanged",
                "argumentBindingPolicy", Map.of("logicalContextKeys", List.of("zone"))))
            .build(), tool);

        assertThat(registry.getToolRevision("policy_tool")).isGreaterThan(firstRevision);
    }
}
