package com.chatchat.common.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelResourceRegistryTest {

    @Test
    void providesOneValidatedViewForAllConsumers() {
        ModelsConfig properties = new ModelsConfig();
        properties.setDefaultChatModel("display-model");
        properties.setAvailableChatModels(List.of("display-model", "broken-model"));
        ModelsConfig.ModelConnectionConfig configured = new ModelsConfig.ModelConnectionConfig();
        configured.setModelName("/models/provider-id");
        configured.setBaseUrl("http://127.0.0.1:31000/v1");
        properties.getChatModels().put("display-model", configured);
        properties.getChatModels().put("broken-model", new ModelsConfig.ModelConnectionConfig());

        ModelResourceRegistry registry = new ModelResourceRegistry(properties);

        assertThat(registry.defaultChatModel()).isEqualTo("display-model");
        assertThat(registry.selectableChatModels()).containsExactly("display-model");
        assertThat(registry.canonicalName("/models/provider-id")).isEqualTo("display-model");
        assertThat(registry.require("display-model").config()).isSameAs(configured);
        assertThatThrownBy(() -> registry.require("broken-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base URL");
    }
}
