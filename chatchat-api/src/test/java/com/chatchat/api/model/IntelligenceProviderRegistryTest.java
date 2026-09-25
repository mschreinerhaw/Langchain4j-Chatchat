package com.chatchat.api.model;

import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntelligenceProviderRegistryTest {
    @Test void exposesBusinessDescriptionAndRoleGovernedMcpMode() {
        PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
        AgentRegistryPort agents = mock(AgentRegistryPort.class);
        AgentDescriptor agent = new AgentDescriptor("group.analysis", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://group.example/a2a"),
            Set.of(CapabilityId.parse("finance.analysis.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of("ToolAnalysisEvidence"),
            null, "", 50, true, Map.of("allowedTenantIds", List.of("tenant-1"),
                "professionalCapabilities", List.of("Portfolio analysis"),
                "analysisGrants", Map.of("skillIds", List.of("investment-skill"),
                    "documentIds", List.of(), "mcpToolNames", List.of(), "mcpRoleGoverned", true)));
        when(agents.list()).thenReturn(List.of(agent));
        var provider = new IntelligenceProviderRegistry(models, agents).list("tenant-1").get(0);
        assertThat(provider.professionalCapabilities()).containsExactly("Portfolio analysis");
        assertThat(provider.mcpRoleGoverned()).isTrue();
        assertThat(provider.mcpToolNames()).isEmpty();
    }

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
