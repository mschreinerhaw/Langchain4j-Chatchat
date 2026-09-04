package com.chatchat.agents.orchestration.planning.execution;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecutionResultCoordinatorTest {

    private final PlanExecutionResultCoordinator coordinator = new PlanExecutionResultCoordinator();

    @Test
    void rejectsSuccessfulAttemptWhenAResultReviewIsUnsatisfied() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(execution(
            1, "mcp_tool", "query_tool", true,
            Map.of("toolResultReviewSatisfied", false,
                "toolResultReviewReason", "required rows are missing"))));
        Map<String, Object> metadata = new LinkedHashMap<>();

        InterpretationPlanRuntime.ExecutionResult reviewed = coordinator.review(
            "initial", result, new ArrayList<>(), metadata);

        assertThat(reviewed.success()).isFalse();
        assertThat(reviewed.status()).isEqualTo("result_unsatisfied");
        assertThat(metadata).containsEntry("interpretationPlanResultSatisfied", false);
    }

    @Test
    void preservesExplicitlyAcceptedPartialEvidence() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(execution(
            1, "mcp_tool", "query_tool", true,
            Map.of("toolResultReviewSatisfied", false,
                "toolResultReviewPartialAccepted", true))));

        assertThat(coordinator.review("initial", result, new ArrayList<>(), new LinkedHashMap<>()))
            .isSameAs(result);
    }

    @Test
    void blocksCompletionUntilMandatoryWorkflowToolsHaveRun() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(
            execution(1, "mcp_tool", "asset_query", true, Map.of()),
            execution(2, "final_answer", "", true, Map.of())));
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<String> observations = new ArrayList<>();

        PlanExecutionResultCoordinator.Outcome outcome = coordinator.consume(
            new PlanExecutionResultCoordinator.Request("initial", plan(), result,
                List.of("asset_query", "detail_query"), List.of(), observations, metadata));

        assertThat(outcome.workflowBlocked()).isTrue();
        assertThat(outcome.result().status()).isEqualTo("MCP_WORKFLOW_INCOMPLETE");
        assertThat(outcome.missingRequiredTools()).containsExactly("detail_query");
        assertThat(metadata).containsEntry("interpretationPlanWorkflowBlocked", true);
    }

    private InterpretationPlan plan() {
        return new InterpretationPlan("1.0",
            new InterpretationPlan.Intent("analysis", "analyze", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of()),
            new InterpretationPlan.ExecutionPolicy(5, false, List.of(), List.of(), 30_000), null);
    }

    private InterpretationPlanRuntime.ExecutionResult result(
        List<InterpretationPlanRuntime.StepExecution> steps) {
        return new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, "done", steps, Map.of(), 1L);
    }

    private InterpretationPlanRuntime.StepExecution execution(int id, String actionType,
        String tool, boolean success, Map<String, Object> metadata) {
        return new InterpretationPlanRuntime.StepExecution(
            id, actionType, tool, success, Map.of(), null, null,
            "final_answer".equals(actionType) ? "done" : null, 1L, metadata);
    }
}
