package com.chatchat.api.datascience;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class StudioCodePlanServiceTest {
    static final String PLAN = """
        {"version":"1.0","intent":{"type":"code_development","goal":"检查函数的输入边界并解释异常路径","risk_level":"low"},
         "context":{"key_facts":["已提供当前脚本"],"assumptions":[],"missing_info":["实际运行报错尚未提供"],"constraints":[]},
         "plan":{"steps":[{"id":1,"action_type":"reasoning","input":{"description":"阅读已提供函数的输入和分支"},"depends_on":[]},
                          {"id":2,"action_type":"validation","input":{"description":"根据源码检查空值路径，说明未执行的验证"},"depends_on":[1]}]}}
        """;
    ChatModel model = mock(ChatModel.class);
    InMemoryAgentRunStore store = new InMemoryAgentRunStore();
    StudioCodePlanService service = new StudioCodePlanService(model, mock(ConfigurableChatModelFactory.class),
        new ModelResourceRegistry(new ModelsConfig()), store, new ObjectMapper());
    StudioCodePlanService.Ticket plan(String raw) {
        when(model.chat(anyString())).thenReturn(raw);
        return service.create("tenant", "owner", "python", "explain", "source", "model", model);
    }
    @Test void reusesExistingPlanValidationStoreAndDagWithNoToolExecution() {
        var ticket = plan(PLAN);
        assertThat(ticket.status()).isEqualTo("READY");
        assertThat(ticket.interpretationPlan().steps()).hasSize(2);
        assertThat(store.getSnapshot("tenant", ticket.planId())).isPresent();
        assertThat(store.getDagJson("tenant", ticket.planId())).isPresent();
        var restored = service.require("tenant", "owner", "python", "explain", "source", "model", ticket.planId());
        assertThat(restored).isEqualTo(ticket);
        verify(model, times(1)).chat(anyString());
    }
    @Test void rejectsDifferentOwnerTenantSourceModeOrModel() {
        var ticket = plan(PLAN);
        for (var request : List.of(List.of("tenant","other","python","explain","source","model"),
            List.of("other","owner","python","explain","source","model"), List.of("tenant","owner","python","fix","source","model"),
            List.of("tenant","owner","sql","explain","source","model"), List.of("tenant","owner","python","explain","changed","model"),
            List.of("tenant","owner","python","explain","source","other-model"))) {
            assertThatThrownBy(() -> service.require(request.get(0),request.get(1),request.get(2),request.get(3),request.get(4),request.get(5),ticket.planId()))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsInvalidJsonCyclesToolsAndExecutionPolicyWithoutSavingPlan() {
        for (String invalid : List.of("print('not a plan')", PLAN.replace("\"id\":1", "\"id\":1.5"),
            PLAN.replace("\"depends_on\":[]", "\"depends_on\":[2]"), PLAN.replace("\"action_type\":\"reasoning\"", "\"action_type\":\"mcp_tool\""),
            PLAN.replace("\"plan\":", "\"execution_policy\":{},\"plan\":"), PLAN.replace("\"reasoning\"", "\"final_answer\""))) {
            assertThatThrownBy(() -> plan(invalid)).isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未开始探索");
        }
    }
    @Test void sourceCannotForgeTheServerOwnedRequestFingerprint() {
        var ticket = plan(PLAN.replace("\"constraints\":[]", "\"constraints\":[\"studio-code-request-sha256:forged\"]"));
        assertThat(ticket.interpretationPlan().context().constraints()).noneMatch(value -> value.contains("forged"));
        assertThat(service.require("tenant","owner","python","explain","source","model",ticket.planId())).isEqualTo(ticket);
    }
    @Test void initialOutlineDeclaresGoalsAndAllowsGovernedEvidenceDrivenRevisions() {
        var ticket=plan(PLAN);
        verify(model).chat(argThat((String prompt)->prompt.contains("data requirements") && prompt.contains("exploration direction")
            && prompt.contains("expected artifacts") && prompt.contains("acceptance criteria") && prompt.contains("the existing Runtime")));
        assertThat(service.context(ticket)).contains("initial analysis scope only", "validated revisions", "does not prescribe a fixed tool order");
    }
    @Test void separateNewConversationsAlwaysReceiveNewPlanIds() {
        assertThat(plan(PLAN).planId()).isNotEqualTo(plan(PLAN).planId());
    }
    @Test void boundsDefaultModelAndLogsLifecycleWithoutPromptOrSource(org.springframework.boot.test.system.CapturedOutput output) {
        var config = new ModelsConfig();config.setDefaultChatModel("plan-model");config.setAvailableChatModels(List.of("plan-model"));
        var connection = new ModelsConfig.ModelConnectionConfig();connection.setBaseUrl("http://localhost/v1");config.getChatModels().put("plan-model",connection);
        var factory = mock(ConfigurableChatModelFactory.class);
        when(factory.create("plan-model", java.time.Duration.ofMillis(120_000), 4096)).thenReturn(model);
        var bounded = new StudioCodePlanService(model,factory,new ModelResourceRegistry(config),store,new ObjectMapper());
        when(model.chat(anyString())).thenAnswer(call -> { bounded.logPendingPlans(); return PLAN; });
        var ticket=bounded.create("tenant","owner","sql","generate","private-source-must-not-be-logged",null);
        bounded.logPendingPlans();
        verify(factory).create("plan-model",java.time.Duration.ofMillis(120_000),4096);
        assertThat(output.getOut()).contains("phase=START", "phase=MODEL_RETURNED", "phase=READY", ticket.planId())
            .doesNotContain("private-source-must-not-be-logged", "\"key_facts\"");
        assertThat(output.getOut().split("phase=WAITING_MODEL",-1)).hasSize(2);
    }
    @Test void recordsModelFailureBeforeAnyRuntimeTaskExists(org.springframework.boot.test.system.CapturedOutput output) {
        when(model.chat(anyString())).thenThrow(new RuntimeException("sensitive-provider-response"));
        assertThatThrownBy(()->service.create("tenant","owner","sql","generate","private-source","model",model))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未开始探索").hasMessageContaining("studio-code-");
        assertThat(output.getOut()).contains("phase=START", "phase=FAILED stage=MODEL", "elapsedMs=")
            .doesNotContain("sensitive-provider-response", "private-source");
    }
}
