package com.chatchat.agents.model;

import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void reportsSelectedAndConfiguredModelsWhenConnectionIsMissing() {
        ModelsConfig config = new ModelsConfig();
        ModelsConfig.ModelConnectionConfig dedicated = new ModelsConfig.ModelConnectionConfig();
        dedicated.setApiKey("test-key");
        dedicated.setBaseUrl("http://127.0.0.1:31005/v1");
        config.getChatModels().put("DeepSeek-V4.1-Flash", dedicated);

        ConfigurableChatModelFactory factory = new ConfigurableChatModelFactory(config, new ObjectMapper());

        assertThatThrownBy(() -> factory.create("unknown-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown-model")
            .hasMessageContaining("DeepSeek-V4.1-Flash");
    }

    @Test
    void reportsMatchedConfigKeyWhenBaseUrlIsBlank() {
        ModelsConfig config = new ModelsConfig();
        ModelsConfig.ModelConnectionConfig dedicated = new ModelsConfig.ModelConnectionConfig();
        dedicated.setApiKey("test-key");
        config.getChatModels().put("[DeepSeek-V4.1-Flash]", dedicated);

        ConfigurableChatModelFactory factory = new ConfigurableChatModelFactory(config, new ObjectMapper());

        assertThatThrownBy(() -> factory.create("DeepSeek-V4.1-Flash"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DeepSeek-V4.1-Flash")
            .hasMessageContaining("matched config key='DeepSeek-V4.1-Flash'");
    }
}
