package com.chatchat.agents.tool;

import org.junit.jupiter.api.Test;

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
}
