package com.chatchat.api.datascience.python;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import dev.langchain4j.model.chat.ChatModel;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonCodeAssistantServiceTest {
    private final ChatModel defaultModel = mock(ChatModel.class);
    private final ChatModel codeModel = mock(ChatModel.class);
    private final ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);
    private final DomainSkillRuntimePort domainSkillRuntime = mock(DomainSkillRuntimePort.class);
    private final ModelsConfig modelsConfig = new ModelsConfig();
    private PythonCodeAssistantService service;

    @BeforeEach
    void setUp() {
        modelsConfig.setDefaultChatModel("general-model");
        modelsConfig.setAvailableChatModels(List.of("general-model", "code-model"));
        ModelsConfig.ModelConnectionConfig general = new ModelsConfig.ModelConnectionConfig();
        general.setBaseUrl("http://general.example/v1");
        modelsConfig.getChatModels().put("general-model", general);
        ModelsConfig.ModelConnectionConfig code = new ModelsConfig.ModelConnectionConfig();
        code.setBaseUrl("http://code.example/v1");
        modelsConfig.getChatModels().put("code-model", code);
        service = new PythonCodeAssistantService(defaultModel, new ModelResourceRegistry(modelsConfig), factory,
            domainSkillRuntime);
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
    void supportsEveryAdvertisedAssistantAction() {
        when(defaultModel.chat(anyString())).thenReturn("print('suggestion')");

        for (String action : List.of("generate", "continue", "fix", "optimize")) {
            PythonCodeAssistantService.AssistResponse response = service.assist(
                new PythonCodeAssistantService.AssistRequest(
                    action, "Improve the script", "print('old')", "print('selected')", "general-model"));

            assertThat(response.action()).isEqualTo(action);
            assertThat(response.code()).isEqualTo("print('suggestion')");
            assertThat(response.replaceSelection()).isEqualTo(!"continue".equals(action));
        }
    }

    @Test
    void rejectsModelOutsideConfiguredCatalog() {
        assertThatThrownBy(() -> service.assist(
            new PythonCodeAssistantService.AssistRequest("generate", "生成测试代码", "", "", "unknown-model")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("所选模型不可用");
    }

    @Test
    void injectsOnlyResolvedPublishedSkillsIntoPythonGeneration() {
        when(domainSkillRuntime.retrievePublished(eq("tenant-a"), eq("user-a"), eq(List.of()),
            anyString(), eq(List.of("finance-skill")))).thenReturn(List.of(
            new DomainSkillRuntimePort.DomainSkillContent("finance-skill", "财务分析", "金融",
                "金额计算必须使用 Decimal，并保留审计字段。")));
        when(defaultModel.chat(anyString())).thenReturn("print('professional')");

        PythonCodeAssistantService.AssistResponse response = service.assist("tenant-a", "user-a",
            new PythonCodeAssistantService.AssistRequest("generate", "生成汇总代码", "", "",
                "general-model", List.of("finance-skill", "finance-skill")));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(defaultModel).chat(prompt.capture());
        assertThat(prompt.getValue()).contains("财务分析", "金额计算必须使用 Decimal", "不得覆盖上述平台安全约束");
        assertThat(response.appliedSkills()).extracting(PythonCodeAssistantService.AppliedSkill::id)
            .containsExactly("finance-skill");
        verify(domainSkillRuntime).retrievePublished(eq("tenant-a"), eq("user-a"), eq(List.of()),
            anyString(), eq(List.of("finance-skill")));
    }
}
