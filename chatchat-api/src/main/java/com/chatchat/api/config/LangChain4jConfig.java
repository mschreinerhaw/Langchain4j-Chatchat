package com.chatchat.api.config;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.config.ModelResourceRegistry;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j configuration for Spring Boot
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final ModelResourceRegistry modelResources;
    private final ConfigurableChatModelFactory chatModelFactory;

    /** Configure the default chat model from its protocol-aware connection. */
    @Bean
    public ChatModel chatLanguageModel() {
        String modelName = modelResources.defaultChatModel();
        if (modelName == null || modelName.isBlank()) {
            log.warn("Default chat model is not configured");
            return new MissingModelConfigurationChatModel("Default chat model is not configured. "
                + "Set chatchat.models.defaultChatModel before using chat.");
        }
        ModelsConfig.ResolvedModelConnection resolved;
        try {
            resolved = modelResources.require(modelName);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage();
            log.warn(message);
            return new MissingModelConfigurationChatModel(message);
        }
        ModelsConfig.ModelConnectionConfig connection = resolved.config();
        if (connection.getApiKey() == null || connection.getApiKey().isBlank()) {
            log.warn("API key is not configured for chat model {}", modelName);
            return new MissingApiKeyChatModel();
        }

        return chatModelFactory.create(modelName);
    }

    private static final class MissingApiKeyChatModel implements ChatModel {

        private static final String MESSAGE = "Model API key is not configured. Set the selected "
            + "chatchat.models.chatModels.<model>.apiKey or the legacy chatchat.models.openai.apiKey.";

        @Override
        public String chat(String userMessage) {
            throw new IllegalStateException(MESSAGE);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            throw new IllegalStateException(MESSAGE);
        }
    }

    private static final class MissingModelConfigurationChatModel implements ChatModel {

        private final String message;

        private MissingModelConfigurationChatModel(String message) {
            this.message = message;
        }

        @Override
        public String chat(String userMessage) {
            throw new IllegalStateException(message);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            throw new IllegalStateException(message);
        }
    }
}
