package com.chatchat.runtime.news.model;

public record NewsCollectResult(
    String executionId,
    Long sourceId,
    int discoveredCount,
    int acceptedCount,
    int duplicateCount,
    int rejectedCount,
    int failedCount,
    String errorMessage
) {
}
