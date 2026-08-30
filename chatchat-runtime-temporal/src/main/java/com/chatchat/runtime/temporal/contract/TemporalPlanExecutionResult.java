package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;

import java.util.List;

/** Terminal projection returned by the deterministic plan execution Workflow. */
public record TemporalPlanExecutionResult(
    String status,
    PlanExecutionContinuation continuation,
    List<InterpretationPlanRuntime.StepExecution> executions,
    String finalAnswer,
    String reason
) {
    public TemporalPlanExecutionResult {
        status = status == null || status.isBlank() ? "FAILED" : status.trim();
        executions = List.copyOf(executions == null ? List.of() : executions);
    }
}
