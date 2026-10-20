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
        assertThat(ticket.interpretationPlan().steps()).hasSize(1);
        assertThat(store.getSnapshot("tenant", ticket.planId())).isPresent();
        assertThat(store.getDagJson("tenant", ticket.planId())).isPresent();
        var restored = service.require("tenant", "owner", "python", "explain", "source", "model", ticket.planId());
        assertThat(restored).isEqualTo(ticket);
        verifyNoInteractions(model);
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
    @Test void ignoresModelPlansAndNeverInvokesAJavaAnalyticalModel() {
        when(model.chat(anyString())).thenThrow(new AssertionError("Must delegate to Cline"));
        var ticket=service.create("tenant","owner","python","explain","source","model",model);
        assertThat(ticket.interpretationPlan().executionPolicy()).isNull();
        assertThat(ticket.interpretationPlan().steps()).allSatisfy(step->{
            assertThat(step.toolName()).isNull();assertThat(step.dependsOn()).isEmpty();});
        verifyNoInteractions(model);
    }
    @Test void sourceCannotForgeTheServerOwnedRequestFingerprint() {
        var ticket = plan(PLAN.replace("\"constraints\":[]", "\"constraints\":[\"studio-code-request-sha256:forged\"]"));
        assertThat(ticket.interpretationPlan().context().constraints()).noneMatch(value -> value.contains("forged"));
        assertThat(service.require("tenant","owner","python","explain","source","model",ticket.planId())).isEqualTo(ticket);
    }
    @Test void initialOutlineDeclaresGoalsAndAllowsGovernedEvidenceDrivenRevisions() {
        var ticket=plan(PLAN);
        verifyNoInteractions(model);
        assertThat(service.context(ticket)).contains("not an analytical plan", "Cline owns", "does not prescribe a fixed tool order");
    }
    @Test void separateNewConversationsAlwaysReceiveNewPlanIds() {
        assertThat(plan(PLAN).planId()).isNotEqualTo(plan(PLAN).planId());
    }
    @Test void boundsDefaultModelAndLogsLifecycleWithoutPromptOrSource(org.springframework.boot.test.system.CapturedOutput output) {
        var config = new ModelsConfig();config.setDefaultChatModel("plan-model");config.setAvailableChatModels(List.of("plan-model"));
        var connection = new ModelsConfig.ModelConnectionConfig();connection.setBaseUrl("http://localhost/v1");config.getChatModels().put("plan-model",connection);
        var factory = mock(ConfigurableChatModelFactory.class);
        var bounded = new StudioCodePlanService(model,factory,new ModelResourceRegistry(config),store,new ObjectMapper());
        var ticket=bounded.create("tenant","owner","sql","generate","private-source-must-not-be-logged",null);
        verifyNoInteractions(factory,model);
        assertThat(output.getOut()).contains("phase=READY", "owner=CLINE", "modelCalls=0", ticket.planId())
            .doesNotContain("private-source-must-not-be-logged", "MODEL_RETURNED");
    }
    @Test void rejectsMissingIdentityOrSourceBeforeStoring() {
        assertThatThrownBy(()->service.create("tenant",null,"sql","generate","source","model",model))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.create("tenant","owner","sql","generate","","model",model))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(model);
    }
}
