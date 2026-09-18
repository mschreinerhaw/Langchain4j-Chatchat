package com.chatchat.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvPropertiesEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearLocationOverride() {
        System.clearProperty(EnvPropertiesEnvironmentPostProcessor.LOCATION_PROPERTY);
    }

    @Test
    void loadsModelConfigurationAndOverridesYaml() throws Exception {
        Path file = tempDir.resolve("env.properties");
        Files.writeString(file, """
            chatchat.models.default-chat-model=DeepSeek-V4.1-Flash
            chatchat.models.available-chat-models[0]=DeepSeek-V4.1-Flash
            chatchat.models.chat-models[DeepSeek-V4.1-Flash].model-name=/models/runtime-id
            chatchat.models.chat-models[DeepSeek-V4.1-Flash].api-key=env-key
            chatchat.models.chat-models[DeepSeek-V4.1-Flash].base-url=http://127.0.0.1:31005/v1
            chatchat.models.chat-models[DeepSeek-V4.1-Flash].protocol=openai
            """, StandardCharsets.UTF_8);
        System.setProperty(EnvPropertiesEnvironmentPostProcessor.LOCATION_PROPERTY, file.toString());

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("yaml", Map.of(
            "chatchat.models.default-chat-model", "yaml-model",
            "chatchat.models.chat-models[yaml-model].base-url", "https://yaml.example/v1"
        )));

        new EnvPropertiesEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication());
        ModelsConfig config = Binder.get(environment)
            .bind("chatchat.models", ModelsConfig.class)
            .orElseThrow(() -> new AssertionError("env.properties model configuration was not bound"));

        assertThat(config.getDefaultChatModel()).isEqualTo("DeepSeek-V4.1-Flash");
        ModelsConfig.ModelConnectionConfig connection =
            config.resolveChatModelConfig("DeepSeek-V4.1-Flash");
        assertThat(connection.getModelName()).isEqualTo("/models/runtime-id");
        assertThat(connection.getApiKey()).isEqualTo("env-key");
        assertThat(connection.getBaseUrl()).isEqualTo("http://127.0.0.1:31005/v1");
    }

    @Test
    void operatingSystemStyleSourcesKeepHigherPrecedence() throws Exception {
        Path file = tempDir.resolve("env.properties");
        Files.writeString(file, "chatchat.models.default-chat-model=file-model\n", StandardCharsets.UTF_8);
        System.setProperty(EnvPropertiesEnvironmentPostProcessor.LOCATION_PROPERTY, file.toString());

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("deploymentOverride", Map.of(
            "chatchat.models.default-chat-model", "command-model"
        )));

        new EnvPropertiesEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("chatchat.models.default-chat-model"))
            .isEqualTo("command-model");
    }

    @Test
    void failsClearlyWhenExplicitFileIsMissing() {
        Path missing = tempDir.resolve("missing.properties");
        System.setProperty(EnvPropertiesEnvironmentPostProcessor.LOCATION_PROPERTY, missing.toString());

        assertThatThrownBy(() -> new EnvPropertiesEnvironmentPostProcessor()
            .postProcessEnvironment(new StandardEnvironment(), new SpringApplication()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not exist")
            .hasMessageContaining(missing.toString());
    }
}
