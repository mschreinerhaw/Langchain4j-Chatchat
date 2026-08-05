package com.chatchat.agents.orchestration;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
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
        when(factory.create("alternate-model", true)).thenReturn(alternateModel);

        AgentChatModelResolver resolver = new AgentChatModelResolver(defaultModel, config, factory);

        assertThat(resolver.resolveChatModel("alternate-model")).isSameAs(alternateModel);
        verify(factory).create("alternate-model", true);
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
            context.register(AgentChatModelResolver.class);
            context.refresh();

            AgentChatModelResolver resolver = context.getBean(AgentChatModelResolver.class);
            assertThat(resolver.resolveChatModel(null)).isSameAs(defaultModel);
        }
    }
}
