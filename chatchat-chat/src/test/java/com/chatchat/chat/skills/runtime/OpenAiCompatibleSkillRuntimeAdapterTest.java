package com.chatchat.chat.skills.runtime;

import com.chatchat.common.config.ModelCatalogOverride;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiCompatibleSkillRuntimeAdapterTest {
    @Test
    void requiresAnExplicitPublishedDatabaseModel() {
        LangChain4jSkillRuntimeAdapter delegate = mock(LangChain4jSkillRuntimeAdapter.class);
        @SuppressWarnings("unchecked") ObjectProvider<ModelCatalogOverride> provider = mock(ObjectProvider.class);
        ModelCatalogOverride catalog = mock(ModelCatalogOverride.class);
        when(provider.getIfAvailable()).thenReturn(catalog);
        when(catalog.hasChatModels()).thenReturn(true);
        OpenAiCompatibleSkillRuntimeAdapter adapter =
            new OpenAiCompatibleSkillRuntimeAdapter(delegate, provider);

        var result = adapter.execute(request(Map.of()));

        assertThat(result.status()).isEqualTo("MODEL_REQUIRED");
        verifyNoInteractions(delegate);
    }

    @Test
    void validatesOpenAiProtocolThenDelegatesToTheLocalAgentRuntime() {
        LangChain4jSkillRuntimeAdapter delegate = mock(LangChain4jSkillRuntimeAdapter.class);
        when(delegate.execute(org.mockito.ArgumentMatchers.any())).thenReturn(
            new AgentRuntimeAdapter.ExecutionResult("COMPLETED", "answer", Map.of()));
        @SuppressWarnings("unchecked") ObjectProvider<ModelCatalogOverride> provider = mock(ObjectProvider.class);
        ModelCatalogOverride catalog = mock(ModelCatalogOverride.class);
        when(provider.getIfAvailable()).thenReturn(catalog);
        when(catalog.hasChatModels()).thenReturn(true);
        ModelsConfig.ModelConnectionConfig connection = new ModelsConfig.ModelConnectionConfig();
        connection.setModelName("provider-model");
        connection.setBaseUrl("https://models.example/v1");
        connection.setProtocol("openai-compatible");
        when(catalog.resolveChatModel("finance-model")).thenReturn(new ModelsConfig.ResolvedModelConnection(
            "finance-model", "finance-model", connection, ModelsConfig.ModelMatchType.CONFIG_KEY));
        OpenAiCompatibleSkillRuntimeAdapter adapter =
            new OpenAiCompatibleSkillRuntimeAdapter(delegate, provider);

        var result = adapter.execute(request(Map.of("modelName", "finance-model")));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.metadata()).containsEntry("adapterId", "openai-compatible-agent-runtime")
            .containsEntry("modelName", "finance-model");
    }

    private AgentRuntimeAdapter.ExecutionRequest request(Map<String, Object> attributes) {
        return new AgentRuntimeAdapter.ExecutionRequest("OPENAI_COMPATIBLE", "query", null,
            null, null, null, attributes);
    }
}
