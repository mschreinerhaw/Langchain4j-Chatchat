package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.orchestration.analysis.loop.AnalysisRefinementCoordinator;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the scheduler-to-refinement boundary with opaque capability names. */
class DependencyRecoveryScenarioTest {
    @ParameterizedTest
    @CsvSource({"stop,false,false", "replan,true,false", "stop,false,true", "replan,true,true"})
    void failedPredecessorDoesNotExecuteDependentAndHonorsRecoveryContract(String policy, boolean replan, boolean semanticRejection) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolNames()).thenReturn(java.util.Set.of("opaque_lookup", "opaque_detail"));
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        if (semanticRejection) {
            when(registry.getWorkflowRole("opaque_lookup"))
                .thenReturn(com.chatchat.common.tool.ToolWorkflowRole.ASSET_DISCOVERY);
        }
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        ToolOutput output = semanticRejection ? ToolOutput.success(Map.of("schemaVersion", "asset_query_result.v1",
            "success", true, "returnedCount", 1, "assets", List.of(Map.of("asset", Map.of("id", "candidate")))))
            : ToolOutput.failure("upstream rejected");
        when(tools.execute(any())).thenReturn(new ToolRuntimeExecution(output,
            ToolMetadata.builder().id("opaque_lookup").build(), null, "failed", Map.of()));
        var plan = new InterpretationPlan("1.0",
            new InterpretationPlan.Intent("data_query", "inspect available evidence", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "opaque_lookup", Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "opaque_detail", Map.of(), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)),
                List.of(), List.of(new InterpretationPlan.DependencyContract(1, 2, true, null,
                    "required predecessor", policy)), List.of(), null),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("opaque_lookup", "opaque_detail"), List.of(), 30000),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of()));
        var runtime = new InterpretationPlanRuntime(tools, new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(registry), null,
            review -> InterpretationPlanRuntime.StepReview.rejected("candidate does not satisfy current scope",
                Map.of("selectedAssetIds", List.of(), "rejectedAssetIds", List.of("candidate"))), null);
        var result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(plan, registry,
            List.of("opaque_lookup", "opaque_detail"), "tenant", "request", "conversation", "user", Map.of()));
        assertThat(result.status()).as("%s", result.errorMessage())
            .isEqualTo(replan ? "DAG_REWRITE_REQUESTED" : "STEP_FAILED");
        assertThat(result.success()).isFalse();
        if (semanticRejection) {
            assertThat(result.steps().get(0).toolExecution().output().isSuccess()).isTrue();
            assertThat(result.steps().get(0).metadata()).containsEntry("semanticCandidateReviewSatisfied", false);
        }
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId).containsExactly(1);
        verify(tools, times(1)).execute(any());
        var refinement = new AnalysisRefinementCoordinator(mock(AgentToolNameResolver.class), 3);
        assertThat(refinement.admitRefinement(result, List.of(result), List.of(),
            List.of("opaque_lookup", "opaque_detail"), 0).allowed()).isEqualTo(replan);
        assertThat(refinement.admitRefinement(result, List.of(result), List.of(),
            List.of("opaque_lookup", "opaque_detail"), 2).allowed()).isFalse();
    }
}
