package com.chatchat.agents.runtime.plan.execution;

import java.util.Objects;

/**
 * Internal non-failure control signal used to yield Agent orchestration to a durable plan
 * execution Workflow. It must be intercepted before generic run-failure handling.
 */
public final class AgentPlanSuspendedException extends RuntimeException {

    private final AgentPlanPipelineContinuation continuation;

    public AgentPlanSuspendedException(AgentPlanPipelineContinuation continuation) {
        super("Agent execution suspended for durable plan execution", null, false, false);
        this.continuation = Objects.requireNonNull(continuation, "continuation");
    }

    public AgentPlanPipelineContinuation continuation() {
        return continuation;
    }
}
