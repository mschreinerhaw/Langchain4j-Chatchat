package com.chatchat.agents.runtime.plan.execution;

/** Internal control signal: a workflow-owned Tool Child must run before step processing resumes. */
public final class DeferredPlanToolExecutionException extends RuntimeException {
    private final PlanToolExecutionCommand command;

    public DeferredPlanToolExecutionException(PlanToolExecutionCommand command) {
        super("Plan tool execution deferred to workflow: " + command.idempotencyKey(), null, false, false);
        this.command = command;
    }

    public PlanToolExecutionCommand command() {
        return command;
    }
}
