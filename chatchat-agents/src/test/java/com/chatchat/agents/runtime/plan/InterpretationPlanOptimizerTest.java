package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretationPlanOptimizerTest {

    @Test
    void prunesNoopStepsDedupesToolCallsAndEnablesParallelHint() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("mixed", "Optimize this plan", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "reasoning", "", Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "web_search", Map.of("query", "x"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "web_search", Map.of("query", "x"), List.of(1), null, null),
                new InterpretationPlan.Step(4, "mcp_tool", "document_search", Map.of("query", "y"), List.of(), null, null),
                new InterpretationPlan.Step(5, "final_answer", "", Map.of("answer", "done"), List.of(2, 3, 4), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(5, null, List.of("web_search", "document_search"), List.of(), 30000),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );

        InterpretationPlanOptimizer.OptimizationResult result = new InterpretationPlanOptimizer().optimize(plan);

        assertThat(result.appliedPasses())
            .contains("PruneNoopPass", "DedupeToolCallPass", "ParallelHintPass");
        assertThat(result.plan().steps()).hasSize(3);
        assertThat(result.plan().executionPolicy().allowParallel()).isTrue();
        assertThat(result.plan().steps()).extracting(InterpretationPlan.Step::id)
            .containsExactly(1, 2, 3);
        assertThat(result.plan().steps().get(2).dependsOn()).containsExactly(1, 2);
    }

    @Test
    void stabilityGuardPreventsPruneAndDedupeWhilePolicyOrderingStillApplies() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("mixed", "Respect stability", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "reasoning", "", Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "web_search", Map.of("query", "x"), List.of(), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "web_search", Map.of("query", "x"), List.of(), null, null),
                    new InterpretationPlan.Step(4, "mcp_tool", "document_search", Map.of("query", "y"), List.of(), null, null),
                    new InterpretationPlan.Step(5, "final_answer", "", Map.of("answer", "done"), List.of(2, 3, 4), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(2, 5, "data.results", "array", false)),
                new InterpretationPlan.Stability(List.of(1), List.of("web_search"), true, List.of("reasoning"))
            ),
            new InterpretationPlan.ExecutionPolicy(
                5,
                null,
                List.of("web_search", "document_search"),
                List.of(),
                30000,
                1,
                "safe_answer",
                Map.of("document_search", 0.9, "web_search", 0.2),
                100.0,
                30000,
                0.7
            ),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );

        InterpretationPlanOptimizer.OptimizationResult result = new InterpretationPlanOptimizer().optimize(plan);

        assertThat(result.appliedPasses())
            .doesNotContain("PruneNoopPass", "DedupeToolCallPass")
            .contains("PolicyAwareOrderingPass", "ParallelHintPass");
        assertThat(result.plan().steps()).hasSize(5);
        assertThat(result.plan().steps().get(0).actionType()).isEqualTo("reasoning");
        assertThat(result.plan().steps().get(1).toolName()).isEqualTo("document_search");
        assertThat(result.plan().plan().stability().stableNodes()).contains(1);
        assertThat(result.plan().plan().edgeContracts()).singleElement()
            .satisfies(contract -> assertThat(contract.to()).isNotNull());
    }
}
