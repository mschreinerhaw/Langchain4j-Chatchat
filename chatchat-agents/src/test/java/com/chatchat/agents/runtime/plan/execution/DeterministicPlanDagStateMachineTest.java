package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicPlanDagStateMachineTest {

    private final DeterministicPlanDagStateMachine machine = new DeterministicPlanDagStateMachine();

    @Test
    void computesStableReadyWavesAndDescendants() {
        InterpretationPlan plan = plan(List.of(
            step(3, List.of(1, 2)), step(1, List.of()),
            step(4, List.of(3)), step(2, List.of())
        ));
        DeterministicPlanDagStateMachine.Graph graph = machine.compile(plan);

        assertThat(machine.ready(graph, List.of(4, 3, 2, 1), List.of()))
            .containsExactly(1, 2);
        assertThat(machine.ready(graph, List.of(4, 3), List.of(2, 1)))
            .containsExactly(3);
        assertThat(machine.descendants(graph, List.of(1)))
            .containsExactlyInAnyOrder(3, 4);
    }

    @Test
    void barrierRejectsPreparedSiblingsUnlessIndependentCommitIsAdmitted() {
        List<DeterministicPlanDagStateMachine.NodeOutcome> outcomes = List.of(
            new DeterministicPlanDagStateMachine.NodeOutcome(2, true),
            new DeterministicPlanDagStateMachine.NodeOutcome(1, false));

        assertThat(machine.decideBarrier(outcomes, false))
            .isEqualTo(new DeterministicPlanDagStateMachine.BarrierDecision(
                List.of(), List.of(2), List.of(1), "REJECT_WAVE"));
        assertThat(machine.decideBarrier(outcomes, true))
            .isEqualTo(new DeterministicPlanDagStateMachine.BarrierDecision(
                List.of(2), List.of(), List.of(1), "COMMIT_INDEPENDENT"));
    }

    private InterpretationPlan plan(List<InterpretationPlan.Step> steps) {
        return new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("analysis", "test", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(steps),
            new InterpretationPlan.ExecutionPolicy(steps.size(), true, List.of("query"), List.of(), 10_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of()));
    }

    private InterpretationPlan.Step step(int id, List<Integer> dependencies) {
        return new InterpretationPlan.Step(
            id, "mcp_tool", "query", Map.of(), dependencies, null, null);
    }
}
