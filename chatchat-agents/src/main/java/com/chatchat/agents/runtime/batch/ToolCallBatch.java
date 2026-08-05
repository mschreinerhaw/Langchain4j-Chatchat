package com.chatchat.agents.runtime.batch;

import java.util.List;

public record ToolCallBatch(
    String batchId,
    BatchExecutionMode executionMode,
    boolean stopOnFailure,
    List<ToolCallRequest> calls
) {
    public ToolCallBatch {
        executionMode = executionMode == null ? BatchExecutionMode.SEQUENTIAL : executionMode;
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /**
     * Child template failures are always isolated. The stop flag remains only
     * for wire compatibility with older planners and is never an execution
     * instruction.
     */
    public boolean failureIsolated() {
        return true;
    }
}
