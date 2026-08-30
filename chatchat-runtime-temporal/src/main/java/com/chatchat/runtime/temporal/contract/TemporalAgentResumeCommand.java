package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;

public record TemporalAgentResumeCommand(
    AgentPlanPipelineContinuation continuation,
    TemporalPlanExecutionResult planResult
) {
    public TemporalAgentResumeCommand {
        if (continuation == null || planResult == null) {
            throw new IllegalArgumentException("Agent continuation and plan result are required");
        }
    }
}
