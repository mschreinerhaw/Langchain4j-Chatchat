package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.orchestration.model.AgentChatModelResolver;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.common.config.ModelsConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatModelResolverTest {

    @Test
    @SuppressWarnings("deprecation")
    void resolvesConfiguredAlternateModelWithoutProviderGate() {
        ModelsConfig config = new ModelsConfig();
        config.setDefaultProvider("custom-provider");
        config.setDefaultChatModel("primary-model");
        ChatModel defaultModel = mock(ChatModel.class);
        ChatModel alternateModel = mock(ChatModel.class);
        ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);
        when(factory.create("alternate-model")).thenReturn(alternateModel);
        when(alternateModel.chat("hello")).thenReturn("world");

        AgentChatModelResolver resolver = new AgentChatModelResolver(defaultModel, config, factory);

        ChatModel resolved = resolver.resolveChatModel("alternate-model");
        assertThat(resolved).isInstanceOf(CapacityGovernedChatModel.class);
        assertThat(resolver.resolveChatModel("alternate-model")).isSameAs(resolved);
        assertThat(resolved.chat("hello")).isEqualTo("world");
        verify(factory).create("alternate-model");
        verify(alternateModel).chat("hello");
    }

    @Test
    void springSelectsTheProductionConstructor() {
        ModelsConfig config = new ModelsConfig();
        ChatModel defaultModel = mock(ChatModel.class);
        ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ModelsConfig.class, () -> config);
            context.registerBean(ChatModel.class, () -> defaultModel);
            context.registerBean(ConfigurableChatModelFactory.class, () -> factory);
            context.registerBean(AgentRuntimeProperties.class, AgentRuntimeProperties::new);
            context.register(AgentChatModelResolver.class);
            context.refresh();

            AgentChatModelResolver resolver = context.getBean(AgentChatModelResolver.class);
            assertThat(resolver.resolveChatModel(null)).isInstanceOf(CapacityGovernedChatModel.class);
        }
    }
}
