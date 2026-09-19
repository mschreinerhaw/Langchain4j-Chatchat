package com.chatchat.runtime.news.temporal.contract;

public record NewsCollectionWorkflowResult(
    String executionId,
    Long sourceId,
    String status,
    int discoveredCount,
    int acceptedCount,
    int duplicateCount,
    int rejectedCount,
    int failedCount,
    String errorMessage
) {
    public static NewsCollectionWorkflowResult skipped(Long sourceId, String reason) {
        return new NewsCollectionWorkflowResult(null, sourceId, "SKIPPED", 0, 0, 0, 0, 0, reason);
    }
}
