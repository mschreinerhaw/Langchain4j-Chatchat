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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private final com.chatchat.api.datascience.StudioCodePlanService plans = mock(com.chatchat.api.datascience.StudioCodePlanService.class);
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
            domainSkillRuntime, plans);
        when(factory.create(eq("general-model"), any(java.time.Duration.class), anyInt())).thenReturn(defaultModel);
        when(factory.create(eq("code-model"), any(java.time.Duration.class), anyInt())).thenReturn(codeModel);
        when(plans.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), eq("python"), anyString(), anyString(), anyString()))
            .thenReturn(new com.chatchat.api.datascience.StudioCodePlanService.Ticket("plan", null, "READY"));
        when(plans.context(org.mockito.ArgumentMatchers.any())).thenReturn("\nAccepted code InterpretationPlan");
    }

    @Test void pythonCodingUsesSharedWorkspaceWithServerDerivedOwnerAndAcceptedPlan() {
        var workspace=mock(com.chatchat.api.runtime.CodingWorkspaceService.class);service.setCodingWorkspace(workspace);
        when(factory.create("code-model")).thenReturn(codeModel);
        when(plans.require(anyString(),anyString(),eq("python"),anyString(),anyString(),anyString(),eq("accepted")))
            .thenReturn(new com.chatchat.api.datascience.StudioCodePlanService.Ticket("accepted",null,"READY"));
        var running=mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);when(running.status()).thenReturn(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.RUNNING);
        when(workspace.submit(any(),anyString(),anyString(),eq("python"),eq("code-model"))).thenReturn(running);
        var request=new PythonCodeAssistantService.AssistRequest("fix","Fix current source","print(1)","","code-model",List.of(),"accepted");
        var body=new PythonCodeAssistantService.CodingAssistRequest(request,"stable-turn","main.py",List.of(java.util.Map.of("path","main.py","content","print(1)")));
        var alice=service.startCoding("tenant","alice",body);var bob=service.startCoding("tenant","bob",body);
        assertThat(alice.get("taskId")).isNotEqualTo(bob.get("taskId"));
        var scopes=ArgumentCaptor.forClass(com.chatchat.common.kernel.KernelDataScope.class);
        verify(workspace,org.mockito.Mockito.times(2)).submit(scopes.capture(),anyString(),org.mockito.ArgumentMatchers.contains("main.py"),eq("python"),eq("code-model"));
        assertThat(scopes.getAllValues()).extracting(com.chatchat.common.kernel.KernelDataScope::userId).containsExactly("alice","bob");
        verify(codeModel,org.mockito.Mockito.never()).chat(anyString());
    }

    @Test void pythonCodingRejectsPathTraversalAndChangedSourceBeforeWorkerSubmission() {
        var workspace=mock(com.chatchat.api.runtime.CodingWorkspaceService.class);service.setCodingWorkspace(workspace);
        when(factory.create("code-model")).thenReturn(codeModel);
        when(plans.require(anyString(),anyString(),eq("python"),anyString(),anyString(),anyString(),eq("accepted")))
            .thenReturn(new com.chatchat.api.datascience.StudioCodePlanService.Ticket("accepted",null,"READY"));
        var request=new PythonCodeAssistantService.AssistRequest("fix","Fix current source","print(1)","","code-model",List.of(),"accepted");
        assertThatThrownBy(()->service.startCoding("tenant","alice",new PythonCodeAssistantService.CodingAssistRequest(request,"turn","../secret.py",List.of())))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.startCoding("tenant","alice",new PythonCodeAssistantService.CodingAssistRequest(request,"turn","main.py",List.of(java.util.Map.of("path","main.py","content","print(2)")))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content differs");
        org.mockito.Mockito.verifyNoInteractions(workspace);
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
    void explanationsAndContextAnswersAreReadOnlyAndKeepTheirFormatting() {
        when(defaultModel.chat(anyString())).thenReturn("说明：\n```python\nprint(1)\n```\n未执行代码。");
        for (String action : List.of("explain", "ask")) {
            var response = service.assist(new PythonCodeAssistantService.AssistRequest(action,"请解释当前代码","print(1)","print(1)","general-model"));
            assertThat(response.action()).isEqualTo(action);
            assertThat(response.replaceSelection()).isFalse();
            assertThat(response.code()).contains("说明：", "```python", "未执行代码");
        }
    }

    @Test
    void generatedTestsAreSeparateFromTheSelectedBusinessCode() {
        when(defaultModel.chat(anyString())).thenReturn("import unittest\nclass TestReport(unittest.TestCase):\n    pass");
        var response = service.assist(new PythonCodeAssistantService.AssistRequest("test","为 analysis.py 生成测试","def total(x): return sum(x)","return sum(x)","general-model"));
        assertThat(response.action()).isEqualTo("test");
        assertThat(response.replaceSelection()).isFalse();
        assertThat(response.code()).contains("unittest");
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

    @Test void planningFailureStopsBeforeCodeGeneration() {
        when(plans.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), eq("python"), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("PLAN_REQUIRED"));
        assertThatThrownBy(() -> service.assist(new PythonCodeAssistantService.AssistRequest("fix", "repair", "broken()", "", "general-model")))
            .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(defaultModel);
    }

    @Test void publishesRealInterpretationPlanBeforeGenerationAndReusesOnlyTheBoundPlan() {
        var resources = new ModelResourceRegistry(modelsConfig);
        var realPlans = new com.chatchat.api.datascience.StudioCodePlanService(defaultModel, factory, resources,
            new com.chatchat.agents.runtime.store.InMemoryAgentRunStore(), new com.fasterxml.jackson.databind.ObjectMapper());
        service = new PythonCodeAssistantService(defaultModel, resources, factory, domainSkillRuntime, realPlans);
        String planJson = """
            {"version":"1.0","intent":{"type":"code_development","goal":"解释当前脚本","risk_level":"low"},
             "context":{"key_facts":[],"assumptions":[],"missing_info":[],"constraints":[]},
             "plan":{"steps":[{"id":1,"action_type":"reasoning","input":{"description":"阅读已提供源码的输入输出"},"depends_on":[]}]}}
            """;
        when(defaultModel.chat(anyString())).thenReturn(planJson, "当前脚本输出 1；未执行代码。");
        var request = new PythonCodeAssistantService.AssistRequest("explain", "解释", "print(1)", "", "general-model");
        var plan = service.plan("tenant", "owner", request);
        assertThat(plan.interpretationPlan().steps()).hasSize(1);
        org.mockito.Mockito.verify(defaultModel,org.mockito.Mockito.times(1)).chat(anyString());
        var result = service.assist("tenant", "owner", new PythonCodeAssistantService.AssistRequest("explain", "解释", "print(1)", "", "general-model", List.of(), plan.planId()));
        assertThat(result.code()).contains("未执行代码");assertThat(result.replaceSelection()).isFalse();assertThat(result.plan()).isEqualTo(plan);
        org.mockito.Mockito.verify(defaultModel,org.mockito.Mockito.times(2)).chat(anyString());
        assertThatThrownBy(() -> service.assist("tenant", "owner", new PythonCodeAssistantService.AssistRequest("explain", "解释", "print(2)", "", "general-model", List.of(), plan.planId())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已变化");
        org.mockito.Mockito.verify(defaultModel,org.mockito.Mockito.times(2)).chat(anyString());
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
        assertThat(prompt.getValue()).contains("财务分析", "金额计算必须使用 Decimal", "must not override the platform safety constraints above");
        assertThat(response.appliedSkills()).extracting(PythonCodeAssistantService.AppliedSkill::id)
            .containsExactly("finance-skill");
        verify(domainSkillRuntime).retrievePublished(eq("tenant-a"), eq("user-a"), eq(List.of()),
            anyString(), eq(List.of("finance-skill")));
    }
}
