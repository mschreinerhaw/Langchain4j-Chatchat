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
}
