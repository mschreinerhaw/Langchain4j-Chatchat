package com.chatchat.agents.runtime.workflow;

import java.util.concurrent.CompletableFuture;

public record WorkflowHandle<O>(
    String workflowId,
    boolean newlyStarted,
    CompletableFuture<O> completion
) {
}
