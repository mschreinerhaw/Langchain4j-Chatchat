package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterpretationPlanIncrementalRecoveryTest {

    @Test
    void restoresFingerprintValidatedMaterializationFromRunStore() {
        InterpretationPlan.Step search = new InterpretationPlan.Step(
            1, "mcp_tool", "evidence_search", Map.of("query", "stable"), List.of(), null, null);
        InterpretationPlan.Step answer = new InterpretationPlan.Step(
            2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null);
        InterpretationPlan plan = plan(search, answer);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool("evidence_search")).thenReturn(true);
        when(registry.getToolMetadata("evidence_search"))
            .thenReturn(ToolMetadata.builder().id("evidence_search").riskLevel("low").build());
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        when(tools.execute(org.mockito.ArgumentMatchers.any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("persisted evidence"))),
            ToolMetadata.builder().id("evidence_search").build(), null, "success", Map.of()));
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InterpretationPlanRuntime.DagExecutionController controller = request ->
            InterpretationPlanRuntime.DagDecision.finalAnswer(2, "done", "complete");
        InterpretationPlanRuntime firstRuntime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), store, controller);

        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan, registry, List.of("evidence_search"), "tenant", "request",
            "conversation", "user", Map.of("__agentRunId", "checkpoint-run"));
        InterpretationPlanRuntime.ExecutionResult first = firstRuntime.execute(request);
        assertThat(first.success()).isTrue();
        assertThat(store.planStepCheckpoints("checkpoint-run")).hasSize(2);

        InterpretationPlanRuntime secondRuntime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), store, controller);
        InterpretationPlanRuntime.ExecutionResult restored = secondRuntime.execute(request);

        assertThat(restored.success()).isTrue();
        assertThat(restored.metadata()).containsEntry("reusedPlanStepIds", List.of(1, 2));
        assertThat(restored.steps()).isEmpty();
        assertThat(restored.finalAnswer()).isEqualTo("done");
        assertThat(restored.metadata()).containsEntry("completedPlanStepIds", List.of(1, 2));
        verify(tools, times(1)).execute(org.mockito.ArgumentMatchers.any());

        InterpretationPlan changedPlan = plan(
            new InterpretationPlan.Step(
                1, "mcp_tool", "evidence_search", Map.of("query", "changed"), List.of(), null, null),
            answer);
        InterpretationPlanRuntime.ExecutionResult recomputed = secondRuntime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                changedPlan, registry, List.of("evidence_search"), "tenant", "request",
                "conversation", "user", Map.of("__agentRunId", "checkpoint-run")));

        assertThat(recomputed.success()).isTrue();
        assertThat(recomputed.steps().get(0).metadata()).doesNotContainKey("reusedFromCheckpoint");
        verify(tools, times(2)).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusesMaterializedFrozenNodeAcrossPlanRevision() {
        InterpretationPlan.Step search = new InterpretationPlan.Step(
            1, "mcp_tool", "evidence_search", Map.of("query", "stable"), List.of(), null, null);
        InterpretationPlan.Step answer = new InterpretationPlan.Step(
            2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("generic", "answer", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(search, answer)),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("evidence_search"), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );
        InterpretationPlanRuntime.StepExecution materialized = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "evidence_search", true,
            Map.of("results", List.of("persisted evidence")), null, null, null, 15L, Map.of());
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool("evidence_search")).thenReturn(true);
        when(registry.getToolMetadata("evidence_search"))
            .thenReturn(ToolMetadata.builder().id("evidence_search").riskLevel("low").build());
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools,
            new InterpretationPlanValidator(),
            request -> InterpretationPlanRuntime.DagDecision.finalAnswer(
                2, "materialized input is available", "done")
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, registry, List.of("evidence_search"), "tenant", "request",
                "conversation", "user", Map.of(
                    "workflowExecutionAttempt", 1,
                    "reusablePlanSteps", List.of(new InterpretationPlanRuntime.ReusableStep(search, materialized))
                )
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(result.steps().get(0).metadata()).containsEntry("reusedFromPlanRevision", true);
        assertThat(result.metadata()).containsEntry("reusedPlanStepIds", List.of(1));
        verify(tools, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    private InterpretationPlan plan(InterpretationPlan.Step... steps) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("generic", "answer", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(steps)),
            new InterpretationPlan.ExecutionPolicy(4, false, List.of("evidence_search"), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );
    }
}
