package com.chatchat.api.datascience;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelsConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonCodeAssistantServiceTest {
    private final ChatModel defaultModel = mock(ChatModel.class);
    private final ChatModel codeModel = mock(ChatModel.class);
    private final ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);
    private final ModelsConfig modelsConfig = new ModelsConfig();
    private PythonCodeAssistantService service;

    @BeforeEach
    void setUp() {
        modelsConfig.setDefaultChatModel("general-model");
        modelsConfig.setAvailableChatModels(List.of("general-model", "code-model"));
        service = new PythonCodeAssistantService(defaultModel, modelsConfig, factory);
    }

    @Test
    void routesCodeGenerationToSelectedConfiguredModel() {
        when(factory.create("code-model")).thenReturn(codeModel);
        when(codeModel.chat(anyString())).thenReturn("print('generated')");

        PythonCodeAssistantService.AssistResponse response = service.assist(
            new PythonCodeAssistantService.AssistRequest("generate", "生成测试代码", "print('old')", "", "code-model"));

        assertThat(response.modelName()).isEqualTo("code-model");
        assertThat(response.code()).isEqualTo("print('generated')");
        verify(codeModel).chat(anyString());
    }

    @Test
    void rejectsModelOutsideConfiguredCatalog() {
        assertThatThrownBy(() -> service.assist(
            new PythonCodeAssistantService.AssistRequest("generate", "生成测试代码", "", "", "unknown-model")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("所选模型不可用");
    }
}
