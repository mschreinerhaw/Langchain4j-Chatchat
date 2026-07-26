package com.chatchat.agents.runtime.batch;

import java.util.List;

public record ToolCallBatchResult(
    String batchId,
    String executionMode,
    String startedAt,
    String completedAt,
    String status,
    Summary summary,
    List<ToolCallResult> results
) {
    public record Summary(
        int total,
        int success,
        int failed,
        int blocked,
        int skipped,
        int remoteToolInvocations
    ) {
    }
}
