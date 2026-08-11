package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretationPlanIncrementalRepairTest {

    private final InterpretationPlanIncrementalRepair repair =
        new InterpretationPlanIncrementalRepair();

    @Test
    void freezesUnaffectedNodesAndOnlyAppliesFailedRegionPatch() {
        InterpretationPlan original = plan(List.of(
            step(1, "asset_search", Map.of("query", "customer"), List.of()),
            step(2, "metadata_search", Map.of("table", "customer"), List.of(1)),
            step(3, "independent_policy", Map.of("scope", "tenant"), List.of(1)),
            finalStep(4, List.of(2, 3))
        ), "original goal");
        InterpretationPlan candidate = plan(List.of(
            step(1, "unauthorized_replacement", Map.of("query", "changed"), List.of()),
            step(2, "metadata_search", Map.of("table", "customer_v2"), List.of(1)),
            finalStep(4, List.of(2, 3))
        ), "changed goal");

        InterpretationPlanIncrementalRepair.RepairRegion region =
            repair.region(original, original.steps().get(1));
        InterpretationPlan merged = repair.apply(original, candidate, original.steps().get(1));

        assertThat(region.affectedStepIds()).containsExactlyInAnyOrder(2, 4);
        assertThat(region.frozenStepIds()).containsExactlyInAnyOrder(1, 3);
        assertThat(merged.intent().goal()).isEqualTo("original goal");
        assertThat(merged.steps()).extracting(InterpretationPlan.Step::id)
            .containsExactly(1, 2, 3, 4);
        assertThat(merged.steps().get(0)).isEqualTo(original.steps().get(0));
        assertThat(merged.steps().get(2)).isEqualTo(original.steps().get(2));
        assertThat(merged.steps().get(1).input()).containsEntry("table", "customer_v2");
    }

    private InterpretationPlan plan(List<InterpretationPlan.Step> steps, String goal) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("generic", goal, "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(steps),
            new InterpretationPlan.ExecutionPolicy(steps.size(), false,
                List.of("asset_search", "metadata_search", "independent_policy"), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.5, 0.1, false, List.of()), List.of())
        );
    }

    private InterpretationPlan.Step step(int id, String tool, Map<String, Object> input, List<Integer> dependencies) {
        return new InterpretationPlan.Step(id, "mcp_tool", tool, input, dependencies, null, null);
    }

    private InterpretationPlan.Step finalStep(int id, List<Integer> dependencies) {
        return new InterpretationPlan.Step(
            id, "final_answer", "", Map.of("answer", "done"), dependencies, null, null);
    }
}
