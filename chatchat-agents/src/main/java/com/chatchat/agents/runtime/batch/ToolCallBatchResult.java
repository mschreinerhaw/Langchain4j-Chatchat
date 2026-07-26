package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;

import java.util.List;

public record ToolCallBatchResult(
    String batchId,
    String executionMode,
    String startedAt,
    String completedAt,
    String status,
    Cardinality cardinality,
    Summary summary,
    List<ToolCallResult> results
) {
    public ToolCallBatchResult(
        String batchId,
        String executionMode,
        String startedAt,
        String completedAt,
        String status,
        Summary summary,
        List<ToolCallResult> results
    ) {
        this(
            batchId, executionMode, startedAt, completedAt, status,
            new Cardinality(
                summary == null ? 0 : summary.total(),
                summary == null ? 0 : summary.total(),
                summary == null ? 0 : summary.remoteToolInvocations(),
                results == null ? 0 : results.size()
            ),
            summary,
            results
        );
    }

    public ToolCallBatchResult {
        results = results == null ? List.of() : List.copyOf(results);
        boolean countMismatch = summary != null
            && (summary.total() != results.size()
                || summary.success() + summary.failed() + summary.blocked() + summary.skipped() != summary.total());
        if (countMismatch
            && DiagnosticRunStateMachine.Outcome.SUCCESS.wireValue().equalsIgnoreCase(status)) {
            status = summary.success() > 0
                ? DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS.wireValue()
                : DiagnosticRunStateMachine.State.FAILED.wireValue();
        }
    }

    public record Cardinality(
        int declaredCheckCount,
        int compiledCallCount,
        int executedCallCount,
        int resultCount
    ) {
    }

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
