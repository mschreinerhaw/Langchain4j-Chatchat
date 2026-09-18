package com.chatchat.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelsConfigTest {

    @Test
    @SuppressWarnings("deprecation")
    void hasNoVendorOrModelSpecificJavaDefaults() {
        ModelsConfig config = new ModelsConfig();

        assertThat(config.getDefaultProvider()).isNull();
        assertThat(config.getDefaultChatModel()).isNull();
        assertThat(config.getAvailableChatModels()).isEmpty();
        assertThat(config.getOpenai().getBaseUrl()).isNull();
    }

    @Test
    void derivesSelectableModelsFromDefaultListAndConnectionMap() {
        ModelsConfig config = new ModelsConfig();
        config.setDefaultChatModel("primary-model");
        config.setAvailableChatModels(List.of("secondary-model", "primary-model"));
        config.getChatModels().put("private-model", new ModelsConfig.ModelConnectionConfig());

        assertThat(config.getAvailableChatModels())
            .containsExactly("primary-model", "secondary-model", "private-model");
    }

    @Test
    void resolvesPerModelConnectionBeforeLegacyFallback() {
        ModelsConfig config = new ModelsConfig();
        config.getOpenai().setBaseUrl("https://legacy.example/v1");
        ModelsConfig.ModelConnectionConfig dedicated = new ModelsConfig.ModelConnectionConfig();
        dedicated.setBaseUrl("https://dedicated.example/invoke");
        config.getChatModels().put("Dedicated-Model", dedicated);

        assertThat(config.resolveChatModelConfig("dedicated-model")).isSameAs(dedicated);
        assertThat(config.resolveChatModelConfig("other-model")).isSameAs(config.getOpenai());
    }

    @Test
    void doesNotFallBackToAnEmptyLegacyConnection() {
        ModelsConfig config = new ModelsConfig();

        assertThat(config.resolveChatModelConfig("missing-model")).isNull();
        assertThat(config.resolveChatModelConnection("missing-model")).isNull();
    }

    @Test
    void exposesOnlyModelsWithUsableConnectionsAndCanonicalizesProviderAliases() {
        ModelsConfig config = new ModelsConfig();
        config.setAvailableChatModels(List.of("working-model", "broken-model"));
        ModelsConfig.ModelConnectionConfig working = new ModelsConfig.ModelConnectionConfig();
        working.setModelName("/models/provider-id");
        working.setBaseUrl("http://127.0.0.1:31000/v1");
        config.getChatModels().put("working-model", working);
        config.getChatModels().put("broken-model", new ModelsConfig.ModelConnectionConfig());
        ModelsConfig.ModelConnectionConfig malformed = new ModelsConfig.ModelConnectionConfig();
        malformed.setBaseUrl("127.0.0.1:31002/v1");
        config.getChatModels().put("malformed-model", malformed);

        assertThat(config.getUsableChatModels()).containsExactly("working-model");
        assertThat(config.canonicalChatModelName("/models/provider-id")).isEqualTo("working-model");
        assertThatThrownBy(() -> config.requireUsableChatModelConnection("broken-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base URL")
            .hasMessageContaining("broken-model");
        assertThatThrownBy(() -> config.requireUsableChatModelConnection("malformed-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute http:// or https:// URL");
    }

    @Test
    void resolvesBracketedConfigKeysAndProviderModelAliases() {
        ModelsConfig config = new ModelsConfig();
        ModelsConfig.ModelConnectionConfig dedicated = new ModelsConfig.ModelConnectionConfig();
        dedicated.setModelName("/models/DeepSeek-V4.1-Flash");
        dedicated.setBaseUrl("http://127.0.0.1:31005/v1");
        config.getChatModels().put("[DeepSeek-V4.1-Flash]", dedicated);

        ModelsConfig.ResolvedModelConnection displayName =
            config.resolveChatModelConnection("DeepSeek-V4.1-Flash");
        ModelsConfig.ResolvedModelConnection providerName =
            config.resolveChatModelConnection("/models/DeepSeek-V4.1-Flash");

        assertThat(displayName.config()).isSameAs(dedicated);
        assertThat(displayName.configuredKey()).isEqualTo("DeepSeek-V4.1-Flash");
        assertThat(providerName.config()).isSameAs(dedicated);
        assertThat(providerName.providerModelName()).isEqualTo("/models/DeepSeek-V4.1-Flash");
        assertThat(config.getAvailableChatModels()).containsExactly("DeepSeek-V4.1-Flash");
    }

    @Test
    void bindsDedicatedModelConnectionFromExternalConfiguration() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("chatchat.models.default-chat-model", "display-model");
        source.put("chatchat.models.chat-models[display-model].model-name", "provider-model");
        source.put("chatchat.models.chat-models[display-model].base-url", "https://gateway.example/invoke");
        source.put("chatchat.models.chat-models[display-model].protocol", "dashscope-text");

        ModelsConfig config = new Binder(source)
            .bind("chatchat.models", Bindable.of(ModelsConfig.class))
            .orElseThrow(() -> new AssertionError("model configuration was not bound"));

        ModelsConfig.ModelConnectionConfig connection = config.resolveChatModelConfig("display-model");
        assertThat(connection.getModelName()).isEqualTo("provider-model");
        assertThat(connection.getBaseUrl()).isEqualTo("https://gateway.example/invoke");
        assertThat(connection.getProtocol()).isEqualTo("dashscope-text");
    }

    @Test
    void bindsEverySelectableModelFromYamlConnectionMap() throws Exception {
        String yaml = """
            chatchat:
              models:
                defaultChatModel: deepseek-v4-pro
                availableChatModels:
                  - deepseek-v4-pro
                chatModels:
                  deepseek-v4-pro:
                    baseUrl: https://api.deepseek.com
                  deepseek-v4-flash:
                    baseUrl: https://api.deepseek.com
                  qwen3.8-max:
                    baseUrl: https://dashscope.example/v1
                  qwen3.7-plus:
                    baseUrl: https://dashscope.example/v1
            """;
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "models-test",
            new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))
        );
        Binder binder = new Binder(
            org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources.get(0))
        );

        ModelsConfig config = binder.bind("chatchat.models", Bindable.of(ModelsConfig.class))
            .orElseThrow(() -> new AssertionError("model YAML configuration was not bound"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(sources.get(0));
        config.setEnvironment(environment);
        config.afterPropertiesSet();

        assertThat(config.getAvailableChatModels()).containsExactly(
            "deepseek-v4-pro",
            "deepseek-v4-flash",
            "qwen3.8-max",
            "qwen3.7-plus"
        );
    }

    @Test
    void bindsBracketedYamlKeyWithDottedDisplayName() throws Exception {
        String yaml = """
            chatchat:
              models:
                defaultChatModel: DeepSeek-V4.1-Flash
                chatModels:
                  "[DeepSeek-V4.1-Flash]":
                    modelName: /models/DeepSeek-V4.1-Flash
                    apiKey: test-key
                    baseUrl: http://127.0.0.1:31005/v1
                    protocol: openai
            """;
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "bracketed-model-test",
            new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))
        );
        Binder binder = new Binder(
            org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources.get(0))
        );

        ModelsConfig config = binder.bind("chatchat.models", Bindable.of(ModelsConfig.class))
            .orElseThrow(() -> new AssertionError("model configuration was not bound"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(sources.get(0));
        config.setEnvironment(environment);
        config.afterPropertiesSet();

        ModelsConfig.ResolvedModelConnection resolved =
            config.resolveChatModelConnection("DeepSeek-V4.1-Flash");
        assertThat(resolved).isNotNull();
        assertThat(resolved.config().getBaseUrl()).isEqualTo("http://127.0.0.1:31005/v1");
        assertThat(resolved.providerModelName()).isEqualTo("/models/DeepSeek-V4.1-Flash");
    }
}
