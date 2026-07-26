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
}
