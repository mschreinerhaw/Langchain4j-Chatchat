package com.chatchat.agents.orchestration;

import com.chatchat.common.config.ModelsConfig;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves chat model instances for agent runs.
 */
class AgentChatModelResolver {

    private final ChatModel defaultChatModel;
    private final ModelsConfig modelsConfig;
    private final Map<String, ChatModel> chatModelsByName = new ConcurrentHashMap<>();

    AgentChatModelResolver(ChatModel defaultChatModel, ModelsConfig modelsConfig) {
        this.defaultChatModel = defaultChatModel;
        this.modelsConfig = modelsConfig;
    }

    ChatModel resolveChatModel(String modelName) {
        String normalized = normalizeModelName(modelName);
        if (normalized == null || normalized.equals(modelsConfig.getDefaultChatModel())) {
            return defaultChatModel;
        }
        if (!"openai".equalsIgnoreCase(modelsConfig.getDefaultProvider())) {
            return defaultChatModel;
        }
        return chatModelsByName.computeIfAbsent(normalized, this::buildOpenAiChatModel);
    }

    private ChatModel buildOpenAiChatModel(String modelName) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .apiKey(modelsConfig.getOpenai().getApiKey())
            .baseUrl(modelsConfig.getOpenai().getBaseUrl())
            .modelName(modelName)
            .timeout(Duration.ofSeconds(modelsConfig.getOpenai().getTimeout()))
            .maxRetries(modelsConfig.getOpenai().getMaxRetries())
            .logRequests(true)
            .logResponses(true);
        HttpClientBuilder httpClientBuilder = resolveOpenAiHttpClientBuilder();
        if (httpClientBuilder != null) {
            builder.httpClientBuilder(httpClientBuilder);
        }
        return builder.build();
    }

    private HttpClientBuilder resolveOpenAiHttpClientBuilder() {
        ModelsConfig.ProxyConfig proxyConfig = modelsConfig.getOpenai().getProxy();
        if (proxyConfig == null || !proxyConfig.isEnabled()
            || proxyConfig.getHost() == null || proxyConfig.getHost().isBlank()
            || proxyConfig.getPort() == null || proxyConfig.getPort() <= 0) {
            return null;
        }
        if ("socks".equalsIgnoreCase(proxyConfig.getType())) {
            return null;
        }
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
            .proxy(ProxySelector.of(new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort())));
        return new JdkHttpClientBuilder().httpClientBuilder(httpClientBuilder);
    }

    private String normalizeModelName(String modelName) {
        return modelName == null || modelName.isBlank() ? null : modelName.trim();
    }
}
