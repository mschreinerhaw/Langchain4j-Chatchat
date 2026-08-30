package com.chatchat.agents.runtime.workflow;

import java.util.Optional;

/**
 * Runtime OS port for durable workflow execution.
 *
 * <p>The local adapter provides single-process execution semantics. A distributed adapter such as
 * Temporal can implement the same lifecycle contract without leaking engine-specific concepts into
 * the Agent runtime.</p>
 */
public interface WorkflowRuntime {

    <I, O> WorkflowHandle<O> start(
        WorkflowStartRequest<I> request,
        WorkflowDefinition<I, O> definition
    );

    boolean cancel(String workflowId, String reason);

    Optional<WorkflowExecutionSnapshot> find(String workflowId);

    int activeExecutionCount();
}
