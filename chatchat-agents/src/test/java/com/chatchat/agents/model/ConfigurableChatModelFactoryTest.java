package com.chatchat.agents.model;

import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurableChatModelFactoryTest {

    @Test
    void usesDedicatedConnectionForSelectedModel() {
        ModelsConfig config = new ModelsConfig();
        config.getOpenai().setBaseUrl("https://legacy.example/v1");
        ModelsConfig.ModelConnectionConfig dedicated = new ModelsConfig.ModelConnectionConfig();
        dedicated.setApiKey("test-key");
        dedicated.setBaseUrl("https://gateway.example/custom/invoke");
        dedicated.setProtocol("dashscope-multimodal");
        dedicated.setModelName("provider-vision-model");
        config.getChatModels().put("vision-model", dedicated);

        ChatModel model = new ConfigurableChatModelFactory(config, new ObjectMapper())
            .create("vision-model");

        assertThat(model).isInstanceOf(DashScopeNativeChatModel.class);
        assertThat(((DashScopeNativeChatModel) model).requestBody(ChatRequest.builder()
            .messages(UserMessage.from("hello"))
            .build()).path("model").asText()).isEqualTo("provider-vision-model");
    }

    @Test
    void keepsLegacyConnectionAsCompatibilityFallback() {
        ModelsConfig config = new ModelsConfig();
        config.getOpenai().setApiKey("test-key");
        config.getOpenai().setBaseUrl("https://legacy.example/v1");
        config.getOpenai().setProtocol("openai");

        ChatModel model = new ConfigurableChatModelFactory(config, new ObjectMapper())
            .create("unmapped-model");

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }
}
