package com.chatchat.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
}
