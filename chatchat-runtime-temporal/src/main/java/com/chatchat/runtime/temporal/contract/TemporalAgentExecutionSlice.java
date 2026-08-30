package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;

/** Serializable Activity result for the Agent suspend/resume loop. */
public record TemporalAgentExecutionSlice(
    String status,
    String outputJson,
    AgentPlanPipelineContinuation suspendedPlan
) {
}
