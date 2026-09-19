package com.chatchat.runtime.temporal.contract.agent;

import com.chatchat.runtime.temporal.contract.plan.TemporalPlanExecutionResult;

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
