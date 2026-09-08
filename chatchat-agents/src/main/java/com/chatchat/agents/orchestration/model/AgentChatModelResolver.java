package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.common.config.ModelsConfig;
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
    private final ModelsConfig modelsConfig;
    private final ConfigurableChatModelFactory chatModelFactory;
    private final Map<String, ChatModel> chatModelsByName = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> governedModelsByName = new ConcurrentHashMap<>();
    private final ModelInvocationCapacityManager modelCapacity;

    public AgentChatModelResolver(ChatModel defaultChatModel, ModelsConfig modelsConfig) {
        this(defaultChatModel, modelsConfig,
            new ConfigurableChatModelFactory(modelsConfig, new ObjectMapper()),
            new AgentRuntimeProperties());
    }

    public AgentChatModelResolver(ChatModel defaultChatModel,
                                  ModelsConfig modelsConfig,
                                  ConfigurableChatModelFactory chatModelFactory) {
        this(defaultChatModel, modelsConfig, chatModelFactory, new AgentRuntimeProperties());
    }

    public AgentChatModelResolver(ChatModel defaultChatModel,
                                  ModelsConfig modelsConfig,
                                  AgentRuntimeProperties runtimeProperties) {
        this(defaultChatModel, modelsConfig,
            new ConfigurableChatModelFactory(modelsConfig, new ObjectMapper()), runtimeProperties);
    }

    @Autowired
    public AgentChatModelResolver(ChatModel defaultChatModel,
                                  ModelsConfig modelsConfig,
                                  ConfigurableChatModelFactory chatModelFactory,
                                  AgentRuntimeProperties runtimeProperties) {
        this.defaultChatModel = defaultChatModel;
        this.modelsConfig = modelsConfig;
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
        String selectedModelName = normalized == null ? modelsConfig.getDefaultChatModel() : normalized;
        if (selectedModelName != null && !selectedModelName.isBlank()) {
            log.info("Agent chat model selected modelName={}", selectedModelName);
        }
        String modelKey = selectedModelName == null || selectedModelName.isBlank()
            ? "default" : selectedModelName.trim();
        return governedModelsByName.computeIfAbsent(modelKey, ignored -> {
            ChatModel resolved = normalized == null || normalized.equals(modelsConfig.getDefaultChatModel())
                ? defaultChatModel
                : chatModelsByName.computeIfAbsent(normalized, chatModelFactory::create);
            return new CapacityGovernedChatModel(modelKey, resolved, modelCapacity);
        });
    }

    /** Returns a secret-free identity snapshot suitable for checkpoint fingerprinting. */
    public Map<String, Object> checkpointModelConfiguration(String modelName, ChatModel resolvedModel) {
        String normalized = normalizeModelName(modelName);
        String selected = normalized == null ? modelsConfig.getDefaultChatModel() : normalized;
        ModelsConfig.ModelConnectionConfig config = modelsConfig.resolveChatModelConfig(selected);
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
