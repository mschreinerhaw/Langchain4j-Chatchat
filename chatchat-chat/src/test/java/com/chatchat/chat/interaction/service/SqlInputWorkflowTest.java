package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.ProblemAnalysisPlan;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlInputWorkflowTest {
    static ProblemAnalysisPlan plan(ProblemAnalysisPlan.SqlIntent intent) {
        return new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY, "SQL task", "source", "SQL", "Model decision",
            List.of(new ProblemAnalysisPlan.Task("SQL task", intent == ProblemAnalysisPlan.SqlIntent.EXECUTE
                ? ProblemAnalysisPlan.Intent.DATA_ANALYSIS : ProblemAnalysisPlan.Intent.DIRECT_ANSWER,
                List.of(), "Actual diagnostics or results")), "", null, intent);
    }
    @Test void syntaxDetectionDoesNotDecideWhetherSelectIsAuthorizedToExecute() {
        for (String query : List.of("SELECT 1", "-- source\nSELECT * FROM sales", "WITH t AS (SELECT 1) SELECT * FROM t",
                "Explain this:\n```sql\nSELECT * FROM sales\n```", "SELECT FROM"))
            assertThat(ProblemAnalysisPlanner.sqlSource(query)).isNotNull();
        for (String query : List.of("Create a generic table design", "What is SQL?", "Select a datasource for me"))
            assertThat(ProblemAnalysisPlanner.sqlSource(query)).isNull();
    }
    @Test void explainAndValidateUseOneModelPlanAndNoBusinessTools() {
        for (var intent : List.of(ProblemAnalysisPlan.SqlIntent.EXPLAIN, ProblemAnalysisPlan.SqlIntent.VALIDATE)) {
            var planner = mock(ProblemAnalysisPlanner.class); var direct = mock(DirectAnswerWorkflow.class);
            var policies = mock(AgentToolPolicyResolver.class); var skill = mock(SkillDefinition.class);
            var request = InteractionRequest.builder().query("SELECT * FROM sales").build();
            var context = InteractionContext.builder().build(); var chosen = plan(intent);
            when(planner.analyze(request, context, skill, List.of())).thenReturn(chosen);
            when(direct.execute(eq(request), any(), eq(skill))).thenReturn(InteractionResponse.builder().answer("Static explanation").build());
            var calls = new AtomicInteger();
            var response = new InteractionWorkflowCoordinator(policies, planner, direct, null).execute(request, context, skill,
                (ctx, p) -> { calls.incrementAndGet(); throw new AssertionError("No business query permitted"); });
            assertThat(response.getAnswer()).isEqualTo("Static explanation"); assertThat(calls).hasValue(0);
            verify(planner, times(1)).analyze(request, context, skill, List.of());
            verify(planner, never()).classify(any(), any(), any(), anyList());
        }
    }
    @Test void executeWithoutDataReturnsAnExplicitInputErrorWithoutSubmittingCoding() {
        var planner = mock(ProblemAnalysisPlanner.class); var direct = mock(DirectAnswerWorkflow.class);
        var request = InteractionRequest.builder().query("SELECT * FROM sales").build();
        when(planner.analyze(eq(request), any(), any(), anyList())).thenReturn(plan(ProblemAnalysisPlan.SqlIntent.EXECUTE));
        var response = new InteractionWorkflowCoordinator(mock(AgentToolPolicyResolver.class), planner, direct, null)
            .execute(request, InteractionContext.builder().build(), mock(SkillDefinition.class),
                (ctx, p) -> { throw new AssertionError("No source was authorized"); });
        assertThat(response.getMetadata().toString()).contains("SQL_DATA_SOURCE_REQUIRED");
        assertThat(response.getAnswer()).contains("SQL 文本不是查询结果"); verifyNoInteractions(direct);
    }
    @Test void selectedCapabilitiesDelegateExecutionToTheExistingGovernedBoundary() {
        var policies = mock(AgentToolPolicyResolver.class); var planner = mock(ProblemAnalysisPlanner.class);
        var direct = mock(DirectAnswerWorkflow.class);
        var request = InteractionRequest.builder().query("SELECT * FROM sales").availableTools(List.of("authorized-query")).build();
        when(policies.planningSnapshot(any(), any())).thenReturn(WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME, "selected"));
        when(planner.analyze(eq(request), any(), any(), anyList())).thenReturn(plan(ProblemAnalysisPlan.SqlIntent.EXECUTE));
        var calls = new AtomicInteger();
        new InteractionWorkflowCoordinator(policies, planner, direct, null).execute(request, InteractionContext.builder().build(),
            mock(SkillDefinition.class), (ctx, p) -> { calls.incrementAndGet(); return InteractionResponse.builder().answer("Permission checked by Runtime").build(); });
        assertThat(calls).hasValue(1); verifyNoInteractions(direct);
    }
}
