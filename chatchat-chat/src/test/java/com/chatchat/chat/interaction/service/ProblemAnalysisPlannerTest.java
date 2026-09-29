package com.chatchat.chat.interaction.service;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.*;
import com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProblemAnalysisPlannerTest {
    @Test @SuppressWarnings("unchecked") void publishesFailedObservationWhenModelFails() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenThrow(new IllegalStateException("model unavailable"));
        var publisher = mock(com.chatchat.agents.runtime.event.AgentRunEventPublisher.class);
        ObjectProvider<com.chatchat.agents.runtime.event.AgentRunEventPublisher> publishers = mock(ObjectProvider.class);
        when(publishers.orderedStream()).thenAnswer(ignored -> java.util.stream.Stream.of(publisher));
        var planner = new ProblemAnalysisPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper(), publishers);
        var plan = planner.analyze(InteractionRequest.builder().query("分析数据").toolInput(Map.of("__agentRunId", "run-1")).build(),
            InteractionContext.builder().build(), mock(SkillDefinition.class));
        assertThat(plan.status()).isEqualTo(ProblemAnalysisPlan.Status.PLANNING_FAILED);
        verify(publisher).publish(argThat(event -> event.payload().get("metadata") instanceof Map<?, ?> metadata
            && "FAILED".equals(metadata.get("eventState"))));
    }
    @Test @SuppressWarnings("unchecked") void routesDirectAnswerFromFencedJsonAndIncludesSelectedPurposes() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("```json\r\n" + READY.replace("ASSET_USAGE_GUIDANCE", "DIRECT_ANSWER") + "\r\n```");
        var planner = new ProblemAnalysisPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper(), mock(ObjectProvider.class));
        var policy = mock(AgentToolPolicyResolver.class);
        org.springframework.test.util.ReflectionTestUtils.setField(planner, "toolPolicyResolver", policy);
        when(policy.planningToolPurposes(any(), any())).thenReturn(List.of(Map.of("tool", "opaque-id", "data_type", "TEMPLATE_QUERY")));
        var plan = planner.analyze(InteractionRequest.builder().query("你好").build(),
            InteractionContext.builder().build(), mock(SkillDefinition.class));
        assertThat(plan.status()).isEqualTo(ProblemAnalysisPlan.Status.READY);
        assertThat(new CapabilityWorkflowRouter().route(plan)).isEqualTo(RuntimeWorkflowFamily.DIRECT_ANSWER);
        verify(model).chat(argThat((String prompt) -> prompt.contains("selectedToolPurposes")
            && prompt.contains("opaque-id") && prompt.contains("TEMPLATE_QUERY")));
    }

    @Test @SuppressWarnings("unchecked") void rejectsMultipleJsonValuesRatherThanSilentlySelectingFirstPlan() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(READY + "{}");
        var planner = new ProblemAnalysisPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper(), mock(ObjectProvider.class));
        assertThat(planner.analyze(InteractionRequest.builder().query("解释用途").build(),
            InteractionContext.builder().build(), mock(SkillDefinition.class)).status())
            .isEqualTo(ProblemAnalysisPlan.Status.PLANNING_FAILED);
    }

    private static final String READY = """
        {"status":"READY","objective":"说明净值比对的适用场景","subject":"净值比对模板","domain":"金融",
         "explanation":"用户需要理解用途，而非执行比对",
         "tasks":[{"objective":"理解模板用途和使用限制","intent":"ASSET_USAGE_GUIDANCE",
                   "dataRequirements":["模板说明","输入输出契约"],"expectedResult":"带证据的使用建议"}],"clarificationQuestion":""}
        """;

    @Test @SuppressWarnings("unchecked") void generatesProblemPlanFromQuestionAndHistoryBeforeRouting() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(READY);
        var planner = new ProblemAnalysisPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper(), mock(ObjectProvider.class));
        var request = InteractionRequest.builder().query("主要分析哪些内容，适合什么场景？")
            .toolInput(Map.of("workflowFamily", "ACTION")).build();
        var context = InteractionContext.builder().history(List.of(new ConversationMemoryService.MessageSnapshot(
            "user", "净值比对模板", 1L, Map.of()))).build();
        var plan = planner.analyze(request, context, mock(SkillDefinition.class));
        assertThat(plan.status()).isEqualTo(ProblemAnalysisPlan.Status.READY);
        assertThat(plan.tasks().get(0).dataRequirements()).contains("输入输出契约");
        assertThat(new CapabilityWorkflowRouter().route(plan)).isEqualTo(RuntimeWorkflowFamily.ASSET_GUIDANCE);
        verify(model, times(1)).chat(argThat((String prompt) -> prompt.contains("净值比对模板")
            && prompt.contains("主要分析哪些内容") && prompt.contains("BEFORE choosing workflows")));
    }

    @Test @SuppressWarnings("unchecked") void malformedPlanFailsClosedWithoutKeywordFallback() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("not JSON");
        var planner = new ProblemAnalysisPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper(), mock(ObjectProvider.class));
        var plan = planner.analyze(InteractionRequest.builder().query("分析数据").build(),
            InteractionContext.builder().build(), mock(SkillDefinition.class));
        assertThat(plan.status()).isEqualTo(ProblemAnalysisPlan.Status.PLANNING_FAILED);
        assertThat(ProblemAnalysisPlanner.executable(plan)).isFalse();
        assertThat(((WorkflowOutcome) ProblemAnalysisPlanner.blockedResponse(plan).getMetadata().get("workflowOutcome"))
            .publicStatus()).isEqualTo("FAILED");
        verify(model, times(1)).chat(anyString());
    }

    @Test @SuppressWarnings("unchecked") void ambiguousReferentRequiresClarificationWithoutDefaultWorkflow() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"status":"NEEDS_CLARIFICATION","objective":"","subject":"","domain":"","explanation":"缺少分析对象",
             "tasks":[],"clarificationQuestion":"请说明你希望分析的对象。"}
            """);
        var planner = new ProblemAnalysisPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper(), mock(ObjectProvider.class));
        var plan = planner.analyze(InteractionRequest.builder().query("帮我看看").build(),
            InteractionContext.builder().build(), mock(SkillDefinition.class));
        assertThat(ProblemAnalysisPlanner.executable(plan)).isFalse();
        assertThat(ProblemAnalysisPlanner.blockedResponse(plan).getAnswer()).isEqualTo("请说明你希望分析的对象。");
    }

    @Test void multipleGoalsArePreservedAndNotSilentlyReducedToOneWorkflow() {
        var plan = new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY, "先解释再执行", "API", "金融", "两个不同目标",
            List.of(new ProblemAnalysisPlan.Task("解释用途", ProblemAnalysisPlan.Intent.ASSET_USAGE_GUIDANCE, List.of("契约"), "说明"),
                new ProblemAnalysisPlan.Task("执行", ProblemAnalysisPlan.Intent.ACTION_EXECUTION, List.of("参数"), "执行记录")), "");
        assertThat(new CapabilityWorkflowRouter().requiredWorkflows(plan)).containsExactly(RuntimeWorkflowFamily.ASSET_GUIDANCE, RuntimeWorkflowFamily.ACTION);
        assertThat(ProblemAnalysisPlanner.executable(plan)).isFalse();
        assertThat(ProblemAnalysisPlanner.blockedResponse(plan).getAnswer()).contains("解释用途", "执行");
    }
}
