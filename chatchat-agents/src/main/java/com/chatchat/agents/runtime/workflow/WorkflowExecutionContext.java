package com.chatchat.agents.runtime.workflow;

import java.util.concurrent.CancellationException;

public interface WorkflowExecutionContext {

    String workflowId();

    int attempt();

    boolean cancellationRequested();

    default void checkCancellation() {
        if (cancellationRequested()) {
            throw new CancellationException("Workflow execution was cancelled");
        }
    }
}
