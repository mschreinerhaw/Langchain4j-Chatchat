package com.chatchat.api.model;

import com.chatchat.common.runtime.agent.AgentRegistryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntelligenceProviderRegistryTest {
    @Test void exposesOnlyPublishedEnabledChatModels() {
        PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
        AgentRegistryPort agents = mock(AgentRegistryPort.class);
        when(agents.list()).thenReturn(List.of());
        when(models.list()).thenReturn(List.of(
            model("published", "chat", true, true),
            model("draft", "chat", false, false),
            model("disabled", "chat", true, false),
            model("vector", "embedding", true, true)));
        IntelligenceProviderRegistry registry = new IntelligenceProviderRegistry(models, agents);
        assertThat(registry.list("tenant-1")).extracting(IntelligenceProviderRegistry.Provider::providerId)
            .containsExactly("llm:published");
        assertThat(registry.admits("tenant-1", "llm:published", "general.analysis.v1")).isTrue();
        assertThat(registry.admits("tenant-1", "llm:draft", "general.analysis.v1")).isFalse();
    }

    private PlatformModelCatalogService.ModelView model(String name, String type,
                                                         boolean published, boolean enabled) {
        return new PlatformModelCatalogService.ModelView(name, name, "", type, name,
            "https://example.invalid", "openai", null, null, null, null,
            enabled, false, false, "user", 1L, published, false, enabled, false);
    }
}
