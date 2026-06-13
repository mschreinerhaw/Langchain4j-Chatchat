package com.chatchat.api.config;

import com.chatchat.common.config.ModelsConfig;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/**
 * LangChain4j configuration for Spring Boot
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final ModelsConfig modelsConfig;

    /**
     * Configure OpenAI chat model
     */
    @Bean
    @ConditionalOnProperty(prefix = "chatchat.models", name = "defaultProvider", havingValue = "openai")
    public ChatModel chatLanguageModel() {
        log.info("Initializing OpenAI Chat Model");
        if (modelsConfig.getOpenai().getApiKey() == null || modelsConfig.getOpenai().getApiKey().isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY or chatchat.models.openai.apiKey is required");
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .apiKey(modelsConfig.getOpenai().getApiKey())
            .baseUrl(modelsConfig.getOpenai().getBaseUrl())
            .modelName(modelsConfig.getDefaultChatModel())
            .timeout(Duration.ofSeconds(modelsConfig.getOpenai().getTimeout()))
            .maxRetries(modelsConfig.getOpenai().getMaxRetries())
            .logRequests(false)
            .logResponses(false);

        HttpClientBuilder httpClientBuilder = resolveOpenAiHttpClientBuilder();
        if (httpClientBuilder != null) {
            builder.httpClientBuilder(httpClientBuilder);
        }

        return builder.build();
    }

    /**
     * Resolves the open ai http client builder.
     *
     * @return the resolved open ai http client builder
     */
    private HttpClientBuilder resolveOpenAiHttpClientBuilder() {
        ModelsConfig.ProxyConfig proxyConfig = modelsConfig.getOpenai().getProxy();
        if (proxyConfig == null || !proxyConfig.isEnabled()) {
            return null;
        }
        if (proxyConfig.getHost() == null || proxyConfig.getHost().isBlank() ||
            proxyConfig.getPort() == null || proxyConfig.getPort() <= 0) {
            log.warn("OpenAI proxy is enabled but host/port is invalid, proxy will be ignored");
            return null;
        }

        String proxyType = proxyConfig.getType() == null ? "http" : proxyConfig.getType().toLowerCase(Locale.ROOT);
        if ("socks".equals(proxyType)) {
            log.warn("OpenAI SOCKS proxy is not supported by the current LangChain4j JDK HTTP client, proxy will be ignored");
            return null;
        }

        log.info("Using OpenAI proxy: {}://{}:{}", proxyType,
            proxyConfig.getHost(), proxyConfig.getPort());
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
            .proxy(ProxySelector.of(new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort())));
        return new JdkHttpClientBuilder().httpClientBuilder(httpClientBuilder);
    }
}
