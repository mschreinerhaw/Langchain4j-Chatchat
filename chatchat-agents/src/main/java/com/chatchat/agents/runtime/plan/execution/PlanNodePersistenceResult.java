package com.chatchat.agents.runtime.plan.execution;

/** Serializable continuation returned after node journal/checkpoint persistence. */
public record PlanNodePersistenceResult(
    PlanExecutionContinuation continuation,
    String status
) {
    public PlanNodePersistenceResult {
        if (continuation == null) {
            throw new IllegalArgumentException("Persisted continuation is required");
        }
        status = status == null || status.isBlank() ? "RUNNING" : status.trim();
    }
}
