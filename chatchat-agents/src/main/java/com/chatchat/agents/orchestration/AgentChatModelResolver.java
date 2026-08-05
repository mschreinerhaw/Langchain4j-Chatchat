package com.chatchat.agents.orchestration;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves chat model instances for agent runs.
 */
@Component
public class AgentChatModelResolver {

    private final ChatModel defaultChatModel;
    private final ModelsConfig modelsConfig;
    private final ConfigurableChatModelFactory chatModelFactory;
    private final Map<String, ChatModel> chatModelsByName = new ConcurrentHashMap<>();

    public AgentChatModelResolver(ChatModel defaultChatModel, ModelsConfig modelsConfig) {
        this(defaultChatModel, modelsConfig,
            new ConfigurableChatModelFactory(modelsConfig, new ObjectMapper()));
    }

    public AgentChatModelResolver(ChatModel defaultChatModel,
                                  ModelsConfig modelsConfig,
                                  ConfigurableChatModelFactory chatModelFactory) {
        this.defaultChatModel = defaultChatModel;
        this.modelsConfig = modelsConfig;
        this.chatModelFactory = chatModelFactory;
    }

    public ChatModel resolveChatModel(String modelName) {
        String normalized = normalizeModelName(modelName);
        if (normalized == null || normalized.equals(modelsConfig.getDefaultChatModel())) {
            return defaultChatModel;
        }
        if (!"openai".equalsIgnoreCase(modelsConfig.getDefaultProvider())) {
            return defaultChatModel;
        }
        return chatModelsByName.computeIfAbsent(normalized,
            model -> chatModelFactory.create(model, true));
    }

    private String normalizeModelName(String modelName) {
        return modelName == null || modelName.isBlank() ? null : modelName.trim();
    }
}
