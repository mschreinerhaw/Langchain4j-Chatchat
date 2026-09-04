package com.chatchat.agents.orchestration.analysis.loop;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisRefinementCoordinatorTest {

    @Test
    void resolvesOnlyAvailableConcreteGapToolsAndExpandsPositiveBudget() {
        AgentToolNameResolver names = mock(AgentToolNameResolver.class);
        when(names.resolveMostSpecificAvailableTool("history_query", List.of("tenant_history_query")))
            .thenReturn("tenant_history_query");
        AnalysisRefinementCoordinator coordinator = new AnalysisRefinementCoordinator(names, 3);
        List<Map<String, Object>> history = List.of(Map.of(
            "nextActions", List.of(Map.of("tool", "history_query"))));

        var required = coordinator.requiredTools(
            history, List.of("tenant_history_query"), false);

        assertThat(required).extracting(item -> item.toolName())
            .containsExactly("tenant_history_query");
        assertThat(coordinator.evidenceDrivenRewriteLimit(1, retrieveMore(), true)).isEqualTo(2);
        assertThat(coordinator.evidenceDrivenRewriteLimit(0, retrieveMore(), true)).isZero();
    }

    @Test
    void neverInventsAnImplementationForAnAbstractCapability() {
        AgentToolNameResolver names = mock(AgentToolNameResolver.class);
        when(names.isAbstractCapability("trend")).thenReturn(true);
        AnalysisRefinementCoordinator coordinator = new AnalysisRefinementCoordinator(names, 3);

        assertThat(coordinator.requiredTools(
            List.of(Map.of("nextActions", List.of(Map.of("tool", "trend")))),
            List.of("generic_search"), false)).isEmpty();
    }

    @Test
    void projectsRepairRootAndOnlySuccessfulReusableSteps() {
        AnalysisRefinementCoordinator coordinator = new AnalysisRefinementCoordinator(
            mock(AgentToolNameResolver.class), 3);
        InterpretationPlan.Step first = step(1, "first_tool");
        InterpretationPlan.Step second = step(2, "second_tool");
        InterpretationPlan plan = plan(List.of(first, second));
        InterpretationPlanRuntime.StepExecution success = execution(1, true);
        InterpretationPlanRuntime.StepExecution failed = execution(2, false);
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "failed", false, false, "failure", null, List.of(success, failed), Map.of(), 1L);

        assertThat(coordinator.repairRootStep(plan, result)).isEqualTo(second);
        assertThat(coordinator.reusableSteps(Map.of(), plan, result))
            .containsOnlyKeys(1);
    }

    private EvidenceAugmentationPolicy.Outcome retrieveMore() {
        return new EvidenceAugmentationPolicy.Outcome(
            EvidenceAugmentationPolicy.CONTRACT_VERSION,
            EvidenceAugmentationPolicy.Decision.RETRIEVE_MORE,
            true, true, "gap remains");
    }

    private InterpretationPlan plan(List<InterpretationPlan.Step> steps) {
        return new InterpretationPlan("1.0",
            new InterpretationPlan.Intent("analysis", "analyze", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(steps),
            new InterpretationPlan.ExecutionPolicy(5, false, List.of(), List.of(), 30_000), null);
    }

    private InterpretationPlan.Step step(int id, String tool) {
        return new InterpretationPlan.Step(id, "mcp_tool", tool, Map.of(), List.of(), null, null);
    }

    private InterpretationPlanRuntime.StepExecution execution(int id, boolean success) {
        return new InterpretationPlanRuntime.StepExecution(
            id, "mcp_tool", id == 1 ? "first_tool" : "second_tool", success,
            Map.of(), success ? null : "failure", null, null, 1L);
    }
}
