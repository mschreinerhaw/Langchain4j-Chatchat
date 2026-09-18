package com.chatchat.common.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.net.URI;
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
public class ModelsConfig implements EnvironmentAware, InitializingBean {

    private static final List<String> CHAT_MODEL_PREFIXES = List.of(
        "chatchat.models.chatModels.",
        "chatchat.models.chat-models."
    );
    private static final List<String> CHAT_MODEL_PROPERTY_SUFFIXES = List.of(
        ".proxy.enabled", ".proxy.host", ".proxy.port", ".proxy.type",
        ".modelName", ".model-name", ".apiKey", ".api-key",
        ".baseUrl", ".base-url", ".protocol", ".timeout",
        ".maxTokens", ".max-tokens", ".maxRetries", ".max-retries"
    );

    private transient ConfigurableEnvironment environment;

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

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment instanceof ConfigurableEnvironment configurable
            ? configurable
            : null;
    }

    @Override
    public void afterPropertiesSet() {
        mergeDottedChatModelKeys();
    }

    /**
     * Spring treats dots in unbracketed map keys as nesting separators. Recover model
     * names such as {@code vendor.model-name} by recognizing only the configured
     * connection-property suffix; no provider or model name is encoded here.
     */
    void mergeDottedChatModelKeys() {
        if (environment == null) {
            return;
        }
        LinkedHashSet<String> assigned = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String propertyName : enumerable.getPropertyNames()) {
                DottedModelProperty property = dottedModelProperty(propertyName);
                if (property == null || !assigned.add(property.modelName() + "\u0000" + property.field())) {
                    continue;
                }
                String value = environment.getProperty(propertyName);
                if (value != null) {
                    applyRecoveredProperty(property, value);
                }
            }
        }
    }

    private DottedModelProperty dottedModelProperty(String propertyName) {
        if (propertyName == null || propertyName.indexOf('[') >= 0) {
            return null;
        }
        String prefix = CHAT_MODEL_PREFIXES.stream()
            .filter(propertyName::startsWith)
            .findFirst()
            .orElse(null);
        if (prefix == null) {
            return null;
        }
        String remainder = propertyName.substring(prefix.length());
        for (String suffix : CHAT_MODEL_PROPERTY_SUFFIXES) {
            if (remainder.endsWith(suffix) && remainder.length() > suffix.length()) {
                String modelName = remainder.substring(0, remainder.length() - suffix.length()).trim();
                if (modelName.contains(".")) {
                    return new DottedModelProperty(modelName, suffix.substring(1));
                }
            }
        }
        return null;
    }

    private void applyRecoveredProperty(DottedModelProperty property, String value) {
        ModelConnectionConfig connection = chatModels.computeIfAbsent(
            property.modelName(), ignored -> new ModelConnectionConfig());
        switch (property.field()) {
            case "modelName", "model-name" -> connection.setModelName(value);
            case "apiKey", "api-key" -> connection.setApiKey(value);
            case "baseUrl", "base-url" -> connection.setBaseUrl(value);
            case "protocol" -> connection.setProtocol(value);
            case "timeout" -> connection.setTimeout(Integer.parseInt(value));
            case "maxTokens", "max-tokens" -> connection.setMaxTokens(Integer.parseInt(value));
            case "maxRetries", "max-retries" -> connection.setMaxRetries(Integer.parseInt(value));
            case "proxy.enabled" -> connection.getProxy().setEnabled(Boolean.parseBoolean(value));
            case "proxy.host" -> connection.getProxy().setHost(value);
            case "proxy.port" -> connection.getProxy().setPort(Integer.parseInt(value));
            case "proxy.type" -> connection.getProxy().setType(value);
            default -> {
                // All accepted fields are enumerated above.
            }
        }
    }

    private record DottedModelProperty(String modelName, String field) {
    }

    public List<String> getAvailableChatModels() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (defaultChatModel != null && !defaultChatModel.isBlank()) {
            names.add(normalizeModelIdentifier(defaultChatModel));
        }
        if (availableChatModels != null) {
            availableChatModels.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(ModelsConfig::normalizeModelIdentifier)
                .forEach(names::add);
        }
        if (chatModels != null) {
            chatModels.keySet().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(ModelsConfig::normalizeModelIdentifier)
                .forEach(names::add);
        }
        names.remove(null);
        return new ArrayList<>(names);
    }

    public ModelConnectionConfig resolveChatModelConfig(String modelName) {
        ResolvedModelConnection resolved = resolveChatModelConnection(modelName);
        return resolved == null ? null : resolved.config();
    }

    /**
     * Returns only selectable models that have a complete connection. This is the
     * list that user-facing model pickers should expose.
     */
    public List<String> getUsableChatModels() {
        return getAvailableChatModels().stream()
            .filter(this::hasUsableChatModelConnection)
            .toList();
    }

    public boolean hasUsableChatModelConnection(String modelName) {
        ResolvedModelConnection resolved = resolveChatModelConnection(modelName);
        return resolved != null && resolved.config() != null
            && isAbsoluteHttpUrl(resolved.config().getBaseUrl());
    }

    /**
     * Resolves and validates a model selected by a user or a persisted Agent.
     * Keeping this validation here ensures default chat and Agent execution use
     * exactly the same configuration rules.
     */
    public ResolvedModelConnection requireUsableChatModelConnection(String modelName) {
        String requested = normalizeModelIdentifier(modelName);
        if (requested == null) {
            throw new IllegalArgumentException("Chat model name must not be blank");
        }
        ResolvedModelConnection resolved = resolveChatModelConnection(requested);
        if (resolved == null || resolved.config() == null) {
            throw new IllegalArgumentException("No connection configuration found for selected chat model '"
                + requested + "'. Configured model keys: " + getConfiguredChatModelKeys()
                + ". Configure chatchat.models.chatModels['" + requested + "'] with an explicit baseUrl.");
        }
        if (!hasText(resolved.config().getBaseUrl())) {
            throw new IllegalArgumentException("Model base URL must not be blank for selected chat model '"
                + requested + "' (matched config key='" + resolved.configuredKey()
                + "', matchType=" + resolved.matchType() + "). Configured model keys: "
                + getConfiguredChatModelKeys());
        }
        if (!isAbsoluteHttpUrl(resolved.config().getBaseUrl())) {
            throw new IllegalArgumentException("Invalid model base URL '" + resolved.config().getBaseUrl()
                + "' for selected chat model '" + requested
                + "'. Expected an absolute http:// or https:// URL.");
        }
        return resolved;
    }

    /** Stable configuration key persisted by Agent definitions. */
    public String canonicalChatModelName(String modelName) {
        ResolvedModelConnection resolved = requireUsableChatModelConnection(modelName);
        return resolved.matchType() == ModelMatchType.LEGACY_OPENAI
            ? resolved.requestedModel()
            : resolved.configuredKey();
    }

    /**
     * Resolves a selectable display name or a provider-side model id to its connection.
     * Bracketed Spring map keys are normalized and an explicitly configured provider
     * {@code modelName} is accepted as an alias. No provider-specific model naming
     * convention is inferred. The legacy OpenAI block is used only when it contains
     * an actual connection, avoiding a misleading blank-base-url fallback.
     */
    public ResolvedModelConnection resolveChatModelConnection(String modelName) {
        String requested = normalizeModelIdentifier(modelName);
        if (requested != null && chatModels != null && !chatModels.isEmpty()) {
            ModelConnectionConfig exact = chatModels.get(requested);
            if (exact != null) {
                return resolved(requested, requested, exact, ModelMatchType.CONFIG_KEY);
            }

            for (Map.Entry<String, ModelConnectionConfig> entry : chatModels.entrySet()) {
                String configuredKey = normalizeModelIdentifier(entry.getKey());
                if (configuredKey != null && configuredKey.equalsIgnoreCase(requested)) {
                    return resolved(requested, configuredKey, entry.getValue(),
                        ModelMatchType.CONFIG_KEY_IGNORE_CASE);
                }
            }

            for (Map.Entry<String, ModelConnectionConfig> entry : chatModels.entrySet()) {
                ModelConnectionConfig config = entry.getValue();
                String providerModel = config == null ? null : normalizeModelIdentifier(config.getModelName());
                if (providerModel != null && providerModel.equals(requested)) {
                    return resolved(requested, normalizeModelIdentifier(entry.getKey()), config,
                        ModelMatchType.PROVIDER_MODEL);
                }
            }

            for (Map.Entry<String, ModelConnectionConfig> entry : chatModels.entrySet()) {
                ModelConnectionConfig config = entry.getValue();
                String providerModel = config == null ? null : normalizeModelIdentifier(config.getModelName());
                if (providerModel != null && providerModel.equalsIgnoreCase(requested)) {
                    return resolved(requested, normalizeModelIdentifier(entry.getKey()), config,
                        ModelMatchType.PROVIDER_MODEL_IGNORE_CASE);
                }
            }

        }

        if (hasLegacyConnection()) {
            return resolved(requested, "openai", openai, ModelMatchType.LEGACY_OPENAI);
        }
        return null;
    }

    /** Model keys suitable for diagnostics; secrets and connection URLs are omitted. */
    public List<String> getConfiguredChatModelKeys() {
        if (chatModels == null || chatModels.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        chatModels.keySet().stream()
            .map(ModelsConfig::normalizeModelIdentifier)
            .filter(key -> key != null && !key.isBlank())
            .forEach(keys::add);
        return new ArrayList<>(keys);
    }

    private ResolvedModelConnection resolved(String requested, String configuredKey,
                                               ModelConnectionConfig config, ModelMatchType matchType) {
        return new ResolvedModelConnection(requested, configuredKey, config, matchType);
    }

    private boolean hasLegacyConnection() {
        return openai != null && (hasText(openai.getBaseUrl())
            || hasText(openai.getApiKey()) || hasText(openai.getModelName()));
    }

    private static String normalizeModelIdentifier(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 2 && normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        if (!hasText(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && hasText(uri.getRawAuthority());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public record ResolvedModelConnection(String requestedModel, String configuredKey,
                                          ModelConnectionConfig config, ModelMatchType matchType) {

        /** Returns the explicitly configured provider id, or the selected key for legacy configurations. */
        public String providerModelName() {
            if (config != null && hasText(config.getModelName())) {
                return config.getModelName().trim();
            }
            return normalizeModelIdentifier(requestedModel);
        }
    }

    public enum ModelMatchType {
        CONFIG_KEY,
        CONFIG_KEY_IGNORE_CASE,
        PROVIDER_MODEL,
        PROVIDER_MODEL_IGNORE_CASE,
        LEGACY_OPENAI
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
