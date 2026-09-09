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
    void admitsSameDiscoveryToolWhenRuntimeRequiresNextPage() {
        AnalysisRefinementCoordinator coordinator = new AnalysisRefinementCoordinator(
            mock(AgentToolNameResolver.class), 3);
        InterpretationPlanRuntime.StepExecution discovery = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "mcp_runtime_api_template_query", true, Map.of(), null,
            null, null, 1L, Map.of("templateDiscoveryContinuationRequired", true));
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "DAG_REWRITE_REQUESTED", false, false, "next page required", null,
            List.of(discovery), Map.of(
                "templateDiscoveryContinuationRequired", true,
                "templateDiscoveryRetryInputChanges", Map.of("cursor", "next-page"),
                "templateCoverageDecision", "NEED_NEXT_PAGE",
                "templateRetrievalOutcome", "PAGE_EXHAUSTED_HAS_MORE"), 1L);

        assertThat(coordinator.admitRefinement(result, List.of(result), List.of(),
            List.of("mcp_runtime_api_template_query"), 0))
            .isEqualTo(new AnalysisRefinementCoordinator.RefinementAdmission(
                true, false, "template_discovery_next_page"));
        assertThat(coordinator.rewriteReason(result, List.of()))
            .contains("TEMPLATE_DISCOVERY_CONTINUATION_REQUIRED", "next-page");
        assertThat(coordinator.templateDiscoveryRewriteLimit(result)).isEqualTo(2);

        InterpretationPlan original = plan(List.of(new InterpretationPlan.Step(
            1, "mcp_tool", "mcp_runtime_api_template_query",
            Map.of("query", "customer trading", "limit", 10), List.of(), null, null)));
        InterpretationPlan modelRewrite = plan(List.of(new InterpretationPlan.Step(
            1, "mcp_tool", "mcp_runtime_api_template_query",
            Map.of("query", "changed by model", "excludeTemplateIds", List.of("old")),
            List.of(), null, null)));

        InterpretationPlan enforced = coordinator.enforceTemplateDiscoveryContinuation(
            original, modelRewrite, result);

        assertThat(enforced.steps().get(0).input()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "query", "customer trading", "limit", 10, "cursor", "next-page"));
        assertThat(coordinator.templateDiscoveryContinuationSatisfied(enforced, result)).isTrue();
    }

    @Test
    void executionFailuresAndGenericGapTextDoNotAuthorizeGraphRewriting() {
        var coordinator = new AnalysisRefinementCoordinator(mock(AgentToolNameResolver.class), 3);
        for (String status : List.of("STEP_FAILED", "NODE_ATTEMPT_PERSISTENCE_FAILED",
                "EDGE_CONTRACT_FAILED", "DAG_ABORTED", "DAG_REWRITE_REQUESTED")) {
            var result = new InterpretationPlanRuntime.ExecutionResult(status, false, false,
                "failure", null, List.of(execution(1, false)), Map.of(), 1L);
            var admission = coordinator.admitRefinement(result, List.of(result),
                List.of(Map.of("missingEvidence", List.of("more data"))), List.of("first_tool"), 0);
            assertThat(admission.allowed()).as(status).isFalse();
            assertThat(admission.structuralRepair()).isFalse();
        }
    }

    @Test
    void structuralRepairIsAllowedOnlyOnce() {
        var coordinator = new AnalysisRefinementCoordinator(mock(AgentToolNameResolver.class), 3);
        var result = new InterpretationPlanRuntime.ExecutionResult("INVALID_PLAN", false, false,
            "dependency cycle", null, List.of(), Map.of(), 1L);
        assertThat(coordinator.admitRefinement(result, List.of(), List.of(), List.of(), 0).allowed()).isTrue();
        assertThat(coordinator.admitRefinement(result, List.of(), List.of(), List.of(), 1).allowed()).isFalse();
    }

    @Test
    void onlyAnUntriedConcreteEvidenceToolCanExtendExecution() {
        AgentToolNameResolver names = mock(AgentToolNameResolver.class);
        when(names.resolveMostSpecificAvailableTool("second_tool", List.of("second_tool")))
            .thenReturn("second_tool");
        when(names.sameToolName("second_tool", "second_tool")).thenReturn(true);
        var coordinator = new AnalysisRefinementCoordinator(names, 3);
        var failed = new InterpretationPlanRuntime.ExecutionResult("STEP_FAILED", false, false,
            "failure", null, List.of(execution(1, false)), Map.of(), 1L);
        var history = List.<Map<String, Object>>of(Map.of("nextActions", List.of(Map.of("tool", "second_tool"))));
        var allowed = coordinator.admitRefinement(failed, List.of(failed), history, List.of("second_tool"), 0);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.structuralRepair()).isFalse();
        var tried = new InterpretationPlanRuntime.ExecutionResult("STEP_FAILED", false, false,
            "failure", null, List.of(execution(2, false)), Map.of(), 1L);
        assertThat(coordinator.admitRefinement(failed, List.of(tried, failed), history,
            List.of("second_tool"), 1).allowed()).isFalse();
    }

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
