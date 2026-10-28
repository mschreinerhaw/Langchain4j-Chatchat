package com.chatchat.agents.model;

import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.config.ModelResourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/** Builds a LangChain4j ChatModel for OpenAI-compatible and native provider URLs. */
@Component
public class ConfigurableChatModelFactory {

    private boolean runtimeStreamingEnabled;
    @org.springframework.beans.factory.annotation.Value("${chatchat.models.runtime-streaming-enabled:false}")
    public void setRuntimeStreamingEnabled(boolean enabled) {runtimeStreamingEnabled=enabled;}
    private final ModelResourceRegistry modelResources;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConfigurableChatModelFactory(ModelResourceRegistry modelResources, ObjectMapper objectMapper) {
        this.modelResources = modelResources;
        this.objectMapper = objectMapper;
    }

    /** Compatibility constructor for non-Spring embedders and focused tests. */
    public ConfigurableChatModelFactory(ModelsConfig modelsConfig, ObjectMapper objectMapper) {
        this(new ModelResourceRegistry(modelsConfig), objectMapper);
    }

    public ChatModel create(String modelName) {
        return create(modelName, null, null);
    }

    /** A caller may impose stricter execution limits without changing the registered connection. */
    public ChatModel create(String modelName, Duration maximumTimeout, Integer maximumOutputTokens) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Chat model name must not be blank");
        }
        return createResolved(modelResources.require(modelName), maximumTimeout, maximumOutputTokens);
    }

    /** Frozen connection, one physical attempt; Runtime owns retry admission. */
    public ChatModel createRuntime(ModelsConfig.ResolvedModelConnection resolved) {
        return createResolved(resolved, null, null, true);
    }

    public ChatModel createResolved(ModelsConfig.ResolvedModelConnection resolved,
            Duration maximumTimeout, Integer maximumOutputTokens) {
        return createResolved(resolved, maximumTimeout, maximumOutputTokens, maximumTimeout != null);
    }

    private ChatModel createResolved(ModelsConfig.ResolvedModelConnection resolved,
            Duration maximumTimeout, Integer maximumOutputTokens, boolean disableRetries) {
        if (maximumTimeout != null && (maximumTimeout.isZero() || maximumTimeout.isNegative()))
            throw new IllegalArgumentException("Model invocation budget has expired");
        ModelsConfig.ModelConnectionConfig config = resolved.config();
        String providerModelName = resolved.providerModelName();
        ModelEndpoint endpoint = ModelEndpoint.resolve(config.getBaseUrl(), config.getProtocol());
        Duration timeout = config.requestTimeout();
        if (maximumTimeout != null && (timeout.isZero() || timeout.compareTo(maximumTimeout) > 0)) timeout = maximumTimeout;
        int outputTokens = maximumOutputTokens == null ? config.getMaxTokens()
            : config.getMaxTokens() > 0 ? Math.min(config.getMaxTokens(), maximumOutputTokens) : maximumOutputTokens;
        int retries = disableRetries ? 0 : config.getMaxRetries();
        if(disableRetries)com.chatchat.agents.orchestration.model.MeteredChatModel.singleAttemptProvider();
        if (endpoint.protocol() == ModelEndpoint.Protocol.DASHSCOPE_NATIVE) {
            return new DashScopeNativeChatModel(
                endpoint.url(), endpoint.multimodal(), config.getApiKey(), providerModelName,
                timeout, outputTokens, retries, httpClient(config.getProxy()), objectMapper);
        }

        if(disableRetries && runtimeStreamingEnabled) {
            var streaming=dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .apiKey(config.getApiKey()).baseUrl(endpoint.url()).modelName(providerModelName)
                .logRequests(false).logResponses(false);
            if(!timeout.isZero() && !timeout.isNegative())streaming.timeout(timeout);
            if(outputTokens>0)streaming.maxTokens(outputTokens);
            var client=openAiHttpClientBuilder(config.getProxy());
            if(client!=null)streaming.httpClientBuilder(client);
            return new RuntimeStreamingChatModel(streaming.build(), timeout);
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .apiKey(config.getApiKey())
            .baseUrl(endpoint.url())
            .modelName(providerModelName)
            .maxRetries(retries)
            .logRequests(false)
            .logResponses(false);
        if (!timeout.isZero() && !timeout.isNegative()) {
            builder.timeout(timeout);
        }
        if (outputTokens > 0) {
            builder.maxTokens(outputTokens);
        }
        HttpClientBuilder httpClientBuilder = openAiHttpClientBuilder(config.getProxy());
        if (httpClientBuilder != null) {
            builder.httpClientBuilder(httpClientBuilder);
        }
        return builder.build();
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
