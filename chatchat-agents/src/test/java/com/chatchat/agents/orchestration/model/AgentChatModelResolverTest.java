package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.orchestration.model.AgentChatModelResolver;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.config.ModelResourceRegistry;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AgentChatModelResolverTest {

    @Test
    @SuppressWarnings("deprecation")
    void resolvesConfiguredAlternateModelWithoutProviderGate() {
        ModelsConfig config = new ModelsConfig();
        config.setDefaultProvider("custom-provider");
        config.setDefaultChatModel("primary-model");
        ModelsConfig.ModelConnectionConfig primaryConnection = new ModelsConfig.ModelConnectionConfig();
        primaryConnection.setBaseUrl("http://primary.example/v1");
        config.getChatModels().put("primary-model", primaryConnection);
        ModelsConfig.ModelConnectionConfig alternateConnection = new ModelsConfig.ModelConnectionConfig();
        alternateConnection.setBaseUrl("http://alternate.example/v1");
        config.getChatModels().put("alternate-model", alternateConnection);
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
    void providerAliasForDefaultModelReusesDefaultInstance() {
        ModelsConfig config = new ModelsConfig();
        config.setDefaultChatModel("primary-model");
        ModelsConfig.ModelConnectionConfig primaryConnection = new ModelsConfig.ModelConnectionConfig();
        primaryConnection.setModelName("/models/primary-provider-id");
        primaryConnection.setBaseUrl("http://primary.example/v1");
        config.getChatModels().put("primary-model", primaryConnection);
        ChatModel defaultModel = mock(ChatModel.class);
        ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);

        AgentChatModelResolver resolver = new AgentChatModelResolver(defaultModel, config, factory);

        assertThat(resolver.resolveChatModel("/models/primary-provider-id"))
            .isInstanceOf(CapacityGovernedChatModel.class);
        verify(factory, never()).create("primary-model");
    }

    @Test
    void springSelectsTheProductionConstructor() {
        ModelsConfig config = new ModelsConfig();
        config.setDefaultChatModel("primary-model");
        ModelsConfig.ModelConnectionConfig primaryConnection = new ModelsConfig.ModelConnectionConfig();
        primaryConnection.setBaseUrl("http://primary.example/v1");
        config.getChatModels().put("primary-model", primaryConnection);
        ChatModel defaultModel = mock(ChatModel.class);
        ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ModelsConfig.class, () -> config);
            context.registerBean(ModelResourceRegistry.class, () -> new ModelResourceRegistry(config));
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
