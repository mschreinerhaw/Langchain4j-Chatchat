package com.chatchat.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for ChatChat models
 */
@Data
@Component
@ConfigurationProperties(prefix = "chatchat.models")
public class ModelsConfig {

    /**
     * Default model provider (e.g., "openai", "ollama", "xinference")
     */
    private String defaultProvider = "openai";

    /**
     * Default chat model name
     */
    private String defaultChatModel = "deepseek-v4-pro";

    /**
     * Candidate chat models for frontend selection.
     */
    private List<String> availableChatModels = new ArrayList<>(List.of("deepseek-v4-pro"));

    /**
     * OpenAI API configuration
     */
    private OpenAIConfig openai = new OpenAIConfig();

    @Data
    public static class OpenAIConfig {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private int timeout = 30;
        private int maxRetries = 3;
        private ProxyConfig proxy = new ProxyConfig();
    }

    @Data
    public static class ProxyConfig {
        private boolean enabled = false;
        private String host;
        private Integer port;
        private String type = "http";
    }

}
