package com.chatchat.api.config;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.config.ModelResourceRegistry;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j configuration for Spring Boot
 */
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final ModelResourceRegistry modelResources;
    private final ConfigurableChatModelFactory chatModelFactory;

    /** Configure the default chat model from its protocol-aware connection. */
    @Bean
    public ChatModel chatLanguageModel() {
        return new ChatModel() {
            private volatile String cachedSignature;
            private volatile ChatModel cachedModel;

            private ChatModel current() {
                String name = modelResources.defaultChatModel();
                if (name == null || name.isBlank()) {
                    throw new IllegalStateException("Default chat model is not configured");
                }
                ModelsConfig.ModelConnectionConfig connection = modelResources.require(name).config();
                String signature = name + "\u0000" + connection.getModelName() + "\u0000"
                    + connection.getBaseUrl() + "\u0000" + connection.getProtocol() + "\u0000"
                    + connection.getApiKey() + "\u0000" + connection.getTimeout() + "\u0000"
                    + connection.getMaxTokens() + "\u0000" + connection.getMaxRetries();
                if (cachedModel != null && signature.equals(cachedSignature)) return cachedModel;
                synchronized (this) {
                    if (cachedModel == null || !signature.equals(cachedSignature)) {
                        cachedModel = chatModelFactory.create(name);
                        cachedSignature = signature;
                    }
                    return cachedModel;
                }
            }

            @Override public String chat(String userMessage) { return current().chat(userMessage); }
            @Override public ChatResponse doChat(ChatRequest request) { return current().chat(request); }
        };
    }

}
