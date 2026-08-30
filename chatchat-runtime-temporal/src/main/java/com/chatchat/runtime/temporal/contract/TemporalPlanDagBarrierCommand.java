package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.plan.execution.DeterministicPlanDagStateMachine;

import java.util.List;

public record TemporalPlanDagBarrierCommand(
    List<DeterministicPlanDagStateMachine.NodeOutcome> outcomes,
    boolean commitIndependentSuccesses
) {
    public TemporalPlanDagBarrierCommand {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }
}
