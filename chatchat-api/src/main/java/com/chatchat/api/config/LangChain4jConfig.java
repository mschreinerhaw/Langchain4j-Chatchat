package com.chatchat.api.config;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelsConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j configuration for Spring Boot
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final ModelsConfig modelsConfig;
    private final ConfigurableChatModelFactory chatModelFactory;

    /**
     * Configure OpenAI chat model
     */
    @Bean
    @ConditionalOnProperty(prefix = "chatchat.models", name = "defaultProvider", havingValue = "openai")
    public ChatModel chatLanguageModel() {
        log.info("Initializing OpenAI Chat Model");
        if (modelsConfig.getOpenai().getApiKey() == null || modelsConfig.getOpenai().getApiKey().isBlank()) {
            log.warn("OpenAI API key is not configured. Chat model calls will fail until chatchat.models.openai.apiKey is set.");
            return new MissingApiKeyChatModel();
        }

        return chatModelFactory.create(modelsConfig.getDefaultChatModel(), false);
    }

    private static final class MissingApiKeyChatModel implements ChatModel {

        private static final String MESSAGE = "OpenAI API key is not configured. Set chatchat.models.openai.apiKey "
            + "or CHATCHAT_MODELS_OPENAI_API_KEY before using chat.";

        @Override
        public String chat(String userMessage) {
            throw new IllegalStateException(MESSAGE);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            throw new IllegalStateException(MESSAGE);
        }
    }
}
