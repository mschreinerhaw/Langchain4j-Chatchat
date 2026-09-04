package com.chatchat.agents.orchestration.presentation;

import com.chatchat.agents.orchestration.planning.model.AgentDecision;

/** Produces stable, non-technical lifecycle descriptions for business-facing progress. */
public final class AgentLifecyclePresentationPolicy {

    private AgentLifecyclePresentationPolicy() {
    }

    public static String planGenerationContent(AgentDecision decision) {
        if (decision == null || decision.interpretationPlan() == null) {
            return "Planner generated the next action.";
        }
        Object valid = decision.executionPlan() == null
            ? null : decision.executionPlan().get("interpretationPlanValid");
        return Boolean.TRUE.equals(valid)
            ? "Planner generated an executable InterpretationPlan DAG."
            : "Planner generated an InterpretationPlan DAG candidate that failed runtime validation.";
    }
}
