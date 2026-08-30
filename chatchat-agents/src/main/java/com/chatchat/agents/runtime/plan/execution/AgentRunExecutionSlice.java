package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.AgentRunResult;

/** Result of one resumable Agent orchestration Activity. */
public record AgentRunExecutionSlice(
    String status,
    AgentRunResult completedResult,
    AgentPlanPipelineContinuation suspendedPlan
) {
    public static final String COMPLETED = "COMPLETED";
    public static final String PLAN_SUSPENDED = "PLAN_SUSPENDED";

    public AgentRunExecutionSlice {
        status = status == null ? "" : status.trim();
        boolean completed = COMPLETED.equals(status);
        boolean suspended = PLAN_SUSPENDED.equals(status);
        if ((!completed && !suspended)
            || completed != (completedResult != null)
            || suspended != (suspendedPlan != null)) {
            throw new IllegalArgumentException(
                "Agent execution slice must contain exactly one completed result or suspended plan");
        }
    }

    public static AgentRunExecutionSlice completed(AgentRunResult result) {
        return new AgentRunExecutionSlice(COMPLETED, result, null);
    }

    public static AgentRunExecutionSlice suspended(AgentPlanPipelineContinuation continuation) {
        return new AgentRunExecutionSlice(PLAN_SUSPENDED, null, continuation);
    }
}
