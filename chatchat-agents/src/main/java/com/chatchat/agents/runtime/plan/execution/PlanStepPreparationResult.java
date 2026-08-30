package com.chatchat.agents.runtime.plan.execution;

import java.util.List;

/** Serializable prepared Ready-node wave. */
public record PlanStepPreparationResult(List<PreparedPlanStep> steps) {
    public PlanStepPreparationResult {
        steps = List.copyOf(steps == null ? List.of() : steps);
    }
}
