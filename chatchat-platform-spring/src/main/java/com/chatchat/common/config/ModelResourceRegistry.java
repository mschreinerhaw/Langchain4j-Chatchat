package com.chatchat.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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
    private ModelCatalogOverride catalog;

    @Autowired(required = false)
    public void setCatalog(ModelCatalogOverride catalog) {
        this.catalog = catalog;
    }

    public String defaultChatModel() {
        return catalog == null || !catalog.hasChatModels()
            ? properties.getDefaultChatModel() : catalog.defaultChatModel();
    }

    public List<String> selectableChatModels() {
        return catalog == null || !catalog.hasChatModels()
            ? properties.getUsableChatModels() : catalog.chatModelNames();
    }

    public ModelsConfig.ResolvedModelConnection resolve(String modelName) {
        return catalog == null || !catalog.hasChatModels()
            ? properties.resolveChatModelConnection(modelName) : catalog.resolveChatModel(modelName);
    }

    public ModelsConfig.ResolvedModelConnection require(String modelName) {
        ModelsConfig.ResolvedModelConnection resolved = resolve(modelName);
        if (catalog == null || !catalog.hasChatModels()) {
            return properties.requireUsableChatModelConnection(modelName);
        }
        if (resolved == null || resolved.config() == null || resolved.config().getBaseUrl() == null
            || resolved.config().getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Model is not available: " + modelName);
        }
        return resolved;
    }

    public String canonicalName(String modelName) {
        ModelsConfig.ResolvedModelConnection resolved = require(modelName);
        return resolved.matchType() == ModelsConfig.ModelMatchType.LEGACY_OPENAI
            ? resolved.requestedModel() : resolved.configuredKey();
    }

    public ModelsConfig.ModelConnectionConfig connection(String modelName) {
        ModelsConfig.ResolvedModelConnection resolved = resolve(modelName);
        return resolved == null ? null : resolved.config();
    }

    public List<String> configuredKeys() {
        return catalog == null || !catalog.hasChatModels()
            ? properties.getConfiguredChatModelKeys() : catalog.chatModelNames();
    }
}
