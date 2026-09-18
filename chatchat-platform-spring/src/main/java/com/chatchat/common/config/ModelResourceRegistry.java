package com.chatchat.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single application boundary for model resources mapped from every Spring
 * configuration source. Business modules use this registry instead of reading
 * raw YAML, environment variables, or configuration maps independently.
 */
@Component
@RequiredArgsConstructor
public class ModelResourceRegistry {

    private final ModelsConfig properties;

    public String defaultChatModel() {
        return properties.getDefaultChatModel();
    }

    public List<String> selectableChatModels() {
        return properties.getUsableChatModels();
    }

    public ModelsConfig.ResolvedModelConnection resolve(String modelName) {
        return properties.resolveChatModelConnection(modelName);
    }

    public ModelsConfig.ResolvedModelConnection require(String modelName) {
        return properties.requireUsableChatModelConnection(modelName);
    }

    public String canonicalName(String modelName) {
        return properties.canonicalChatModelName(modelName);
    }

    public ModelsConfig.ModelConnectionConfig connection(String modelName) {
        ModelsConfig.ResolvedModelConnection resolved = resolve(modelName);
        return resolved == null ? null : resolved.config();
    }

    public List<String> configuredKeys() {
        return properties.getConfiguredChatModelKeys();
    }
}
