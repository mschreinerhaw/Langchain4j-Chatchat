package com.chatchat.agents.orchestration.planning.model;

import com.chatchat.agents.runtime.plan.InterpretationPlan;

import java.util.Map;

/** Immutable semantic decision produced by the planning boundary. */
public record AgentDecision(
    String action,
    String toolName,
    Map<String, Object> arguments,
    String answer,
    String reason,
    Map<String, Object> executionPlan,
    Boolean sufficient,
    InterpretationPlan interpretationPlan
) {
    public AgentDecision(String action,
                         String toolName,
                         Map<String, Object> arguments,
                         String answer,
                         String reason,
                         Map<String, Object> executionPlan,
                         Boolean sufficient) {
        this(action, toolName, arguments, answer, reason, executionPlan, sufficient, null);
    }
}
