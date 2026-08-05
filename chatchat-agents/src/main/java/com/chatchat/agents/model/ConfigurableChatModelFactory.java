package com.chatchat.agents.model;

import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/** Builds a LangChain4j ChatModel for OpenAI-compatible and native provider URLs. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigurableChatModelFactory {

    private final ModelsConfig modelsConfig;
    private final ObjectMapper objectMapper;

    public ChatModel create(String modelName, boolean logTraffic) {
        ModelsConfig.OpenAIConfig config = modelsConfig.getOpenai();
        ModelEndpoint endpoint = ModelEndpoint.resolve(config.getBaseUrl(), config.getProtocol());
        Duration timeout = resolveTimeout(config.getTimeout());
        log.info("Initializing chat model protocol={} endpoint={} model={}",
            endpoint.protocol(), endpoint.url(), modelName);
        if (endpoint.protocol() == ModelEndpoint.Protocol.DASHSCOPE_NATIVE) {
            return new DashScopeNativeChatModel(
                endpoint.url(), endpoint.multimodal(), config.getApiKey(), modelName,
                timeout, config.getMaxTokens(), config.getMaxRetries(), httpClient(config.getProxy()), objectMapper);
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .apiKey(config.getApiKey())
            .baseUrl(endpoint.url())
            .modelName(modelName)
            .maxRetries(config.getMaxRetries())
            .logRequests(logTraffic)
            .logResponses(logTraffic);
        if (!timeout.isZero() && !timeout.isNegative()) {
            builder.timeout(timeout);
        }
        if (config.getMaxTokens() > 0) {
            builder.maxTokens(config.getMaxTokens());
        }
        HttpClientBuilder httpClientBuilder = openAiHttpClientBuilder(config.getProxy());
        if (httpClientBuilder != null) {
            builder.httpClientBuilder(httpClientBuilder);
        }
        return builder.build();
    }

    private Duration resolveTimeout(int timeout) {
        if (timeout <= 0) {
            return Duration.ZERO;
        }
        return timeout >= 1000 ? Duration.ofMillis(timeout) : Duration.ofSeconds(timeout);
    }

    private HttpClient httpClient(ModelsConfig.ProxyConfig proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (validHttpProxy(proxy)) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }

    private HttpClientBuilder openAiHttpClientBuilder(ModelsConfig.ProxyConfig proxy) {
        if (!validHttpProxy(proxy)) {
            return null;
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
            .proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        return new JdkHttpClientBuilder().httpClientBuilder(builder);
    }

    private boolean validHttpProxy(ModelsConfig.ProxyConfig proxy) {
        return proxy != null && proxy.isEnabled()
            && proxy.getHost() != null && !proxy.getHost().isBlank()
            && proxy.getPort() != null && proxy.getPort() > 0
            && !"socks".equalsIgnoreCase(proxy.getType());
    }
}
