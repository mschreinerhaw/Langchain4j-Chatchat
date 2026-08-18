package com.chatchat.agents.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
}
