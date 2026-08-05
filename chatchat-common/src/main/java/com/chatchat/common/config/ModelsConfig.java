package com.chatchat.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for ChatChat models
 */
@Data
@Component
@ConfigurationProperties(prefix = "chatchat.models")
public class ModelsConfig {

    /** Legacy metadata retained for configuration compatibility; protocol selects the client. */
    @Deprecated
    private String defaultProvider;

    /**
     * Default chat model name
     */
    private String defaultChatModel;

    /**
     * Candidate chat models for frontend selection.
     */
    private List<String> availableChatModels = new ArrayList<>();

    /**
     * Optional connection overrides keyed by the externally selectable model name.
     * Models not present here use the legacy {@link #openai} connection block.
     */
    private Map<String, ModelConnectionConfig> chatModels = new LinkedHashMap<>();

    private int contextWindowMaxTokens = 200_000;
    private int contextReservedSystemTokens = 20_000;
    private int contextReservedHistoryTokens = 30_000;
    private int contextReservedOutputTokens = 30_000;

    /** Legacy shared connection used when a model has no dedicated chatModels entry. */
    private OpenAIConfig openai = new OpenAIConfig();

    public List<String> getAvailableChatModels() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (defaultChatModel != null && !defaultChatModel.isBlank()) {
            names.add(defaultChatModel.trim());
        }
        if (availableChatModels != null) {
            availableChatModels.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .forEach(names::add);
        }
        if (chatModels != null) {
            chatModels.keySet().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .forEach(names::add);
        }
        return new ArrayList<>(names);
    }

    public ModelConnectionConfig resolveChatModelConfig(String modelName) {
        if (modelName != null && chatModels != null) {
            ModelConnectionConfig exact = chatModels.get(modelName.trim());
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, ModelConnectionConfig> entry : chatModels.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(modelName.trim())) {
                    return entry.getValue();
                }
            }
        }
        return openai;
    }

    @Data
    public static class ModelConnectionConfig {
        /** Optional provider-side model id when it differs from the selectable map key. */
        private String modelName;
        private String apiKey;
        private String baseUrl;
        /**
         * Model wire protocol: auto, openai, dashscope-native,
         * dashscope-multimodal, or dashscope-text.
         * Auto detects full DashScope generation endpoints and OpenAI-compatible URLs.
         */
        private String protocol = "auto";
        private int timeout = 30;
        /**
         * Maximum completion tokens sent to the model. -1 means do not set a model-side limit.
         */
        private int maxTokens = -1;
        private int maxRetries = 3;
        private ProxyConfig proxy = new ProxyConfig();
    }

    /** Legacy connection type retained for chatchat.models.openai compatibility. */
    public static class OpenAIConfig extends ModelConnectionConfig {
    }

    @Data
    public static class ProxyConfig {
        private boolean enabled = false;
        private String host;
        private Integer port;
        private String type = "http";
    }

}
