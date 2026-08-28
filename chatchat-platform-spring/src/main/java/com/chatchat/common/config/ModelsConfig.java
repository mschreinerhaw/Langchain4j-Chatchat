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
            names.add(defaultChatModel.trim());
        }
        if (availableChatModels != null) {
            availableChatModels.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .forEach(names::add);
        }
        if (chatModels != null) {
            chatModels.keySet().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .forEach(names::add);
        }
        return new ArrayList<>(names);
    }

    public ModelConnectionConfig resolveChatModelConfig(String modelName) {
        if (modelName != null && chatModels != null) {
            ModelConnectionConfig exact = chatModels.get(modelName.trim());
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, ModelConnectionConfig> entry : chatModels.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(modelName.trim())) {
                    return entry.getValue();
                }
            }
        }
        return openai;
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
