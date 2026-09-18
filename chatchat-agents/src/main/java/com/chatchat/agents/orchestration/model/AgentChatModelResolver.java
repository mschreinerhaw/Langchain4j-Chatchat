package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.config.ModelResourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves chat model instances for agent runs.
 */
@Component
@Slf4j
public class AgentChatModelResolver {

    private final ChatModel defaultChatModel;
    private final ModelResourceRegistry modelResources;
    private final ConfigurableChatModelFactory chatModelFactory;
    private final Map<String, ChatModel> chatModelsByName = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> governedModelsByName = new ConcurrentHashMap<>();
    private final ModelInvocationCapacityManager modelCapacity;

    public AgentChatModelResolver(ChatModel defaultChatModel, ModelsConfig modelsConfig) {
        this(defaultChatModel, new ModelResourceRegistry(modelsConfig),
            new ConfigurableChatModelFactory(modelsConfig, new ObjectMapper()),
            new AgentRuntimeProperties());
    }

    public AgentChatModelResolver(ChatModel defaultChatModel,
                                  ModelsConfig modelsConfig,
                                  ConfigurableChatModelFactory chatModelFactory) {
        this(defaultChatModel, new ModelResourceRegistry(modelsConfig),
            chatModelFactory, new AgentRuntimeProperties());
    }

    public AgentChatModelResolver(ChatModel defaultChatModel,
                                  ModelsConfig modelsConfig,
                                  AgentRuntimeProperties runtimeProperties) {
        this(defaultChatModel, new ModelResourceRegistry(modelsConfig),
            new ConfigurableChatModelFactory(modelsConfig, new ObjectMapper()), runtimeProperties);
    }

    @Autowired
    public AgentChatModelResolver(ChatModel defaultChatModel,
                                  ModelResourceRegistry modelResources,
                                  ConfigurableChatModelFactory chatModelFactory,
                                  AgentRuntimeProperties runtimeProperties) {
        this.defaultChatModel = defaultChatModel;
        this.modelResources = modelResources;
        this.chatModelFactory = chatModelFactory;
        AgentRuntimeProperties configured = runtimeProperties == null
            ? new AgentRuntimeProperties() : runtimeProperties;
        this.modelCapacity = new ModelInvocationCapacityManager(
            configured.modelMaxConcurrentPerModel(),
            configured.modelMaxRequestsPerSecond(),
            configured.modelCapacityAcquireTimeoutMs());
    }

    public ChatModel resolveChatModel(String modelName) {
        String normalized = normalizeModelName(modelName);
        String selectedModelName = normalized == null ? modelResources.defaultChatModel() : normalized;
        ModelsConfig.ResolvedModelConnection selected =
            modelResources.require(selectedModelName);
        String modelKey = modelResources.canonicalName(selectedModelName);
        log.info("Agent chat model selected modelName={} configKey={} providerModel={} matchType={}",
            selectedModelName, selected.configuredKey(), selected.providerModelName(), selected.matchType());
        return governedModelsByName.computeIfAbsent(modelKey, ignored -> {
            ChatModel resolved = isDefaultSelection(selected)
                ? defaultChatModel
                : chatModelsByName.computeIfAbsent(modelKey, chatModelFactory::create);
            return new CapacityGovernedChatModel(modelKey, resolved, modelCapacity);
        });
    }

    private boolean isDefaultSelection(ModelsConfig.ResolvedModelConnection selected) {
        String defaultName = normalizeModelName(modelResources.defaultChatModel());
        if (defaultName == null) {
            return false;
        }
        ModelsConfig.ResolvedModelConnection defaultConnection =
            modelResources.require(defaultName);
        return java.util.Objects.equals(selected.configuredKey(), defaultConnection.configuredKey())
            && java.util.Objects.equals(selected.providerModelName(), defaultConnection.providerModelName());
    }

    /** Returns a secret-free identity snapshot suitable for checkpoint fingerprinting. */
    public Map<String, Object> checkpointModelConfiguration(String modelName, ChatModel resolvedModel) {
        String normalized = normalizeModelName(modelName);
        String selected = normalized == null ? modelResources.defaultChatModel() : normalized;
        ModelsConfig.ModelConnectionConfig config = modelResources.connection(selected);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("selectedModel", selected == null ? "default" : selected);
        Class<?> implementation = resolvedModel instanceof CapacityGovernedChatModel governed
            ? governed.delegateType()
            : resolvedModel == null ? null : resolvedModel.getClass();
        identity.put("implementation", implementation == null ? "none" : implementation.getName());
        if (config != null) {
            identity.put("providerModel", config.getModelName() == null ? "" : config.getModelName());
            identity.put("baseUrl", config.getBaseUrl() == null ? "" : config.getBaseUrl());
            identity.put("protocol", config.getProtocol() == null ? "" : config.getProtocol());
            identity.put("timeout", config.getTimeout());
            identity.put("maxTokens", config.getMaxTokens());
            identity.put("maxRetries", config.getMaxRetries());
            if (config.getProxy() != null) {
                identity.put("proxy", Map.of(
                    "enabled", config.getProxy().isEnabled(),
                    "host", config.getProxy().getHost() == null ? "" : config.getProxy().getHost(),
                    "port", config.getProxy().getPort() == null ? 0 : config.getProxy().getPort(),
                    "type", config.getProxy().getType() == null ? "" : config.getProxy().getType()
                ));
            }
        }
        return Map.copyOf(identity);
    }

    private String normalizeModelName(String modelName) {
        return modelName == null || modelName.isBlank() ? null : modelName.trim();
    }
}
