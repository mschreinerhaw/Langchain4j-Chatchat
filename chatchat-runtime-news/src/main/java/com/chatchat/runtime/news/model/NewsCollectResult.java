package com.chatchat.runtime.news.model;

import java.time.Instant;

public record NewsCollectResult(
    String executionId,
    Long sourceId,
    int discoveredCount,
    int acceptedCount,
    int duplicateCount,
    int rejectedCount,
    int failedCount,
    String errorMessage,
    Boolean robotsAllowed,
    String robotsStatus,
    String robotsUrl,
    String robotsOverrideReason,
    Instant robotsOverrideUntil
) {
    public NewsCollectResult(String executionId, Long sourceId, int discoveredCount, int acceptedCount,
                             int duplicateCount, int rejectedCount, int failedCount, String errorMessage) {
        this(executionId, sourceId, discoveredCount, acceptedCount, duplicateCount, rejectedCount,
            failedCount, errorMessage, null, null, null, null, null);
    }

    public NewsCollectResult withRobots(Boolean allowed, String status, String url) {
        return withRobots(allowed, status, url, null, null);
    }

    public NewsCollectResult withRobots(Boolean allowed, String status, String url,
                                        String overrideReason, Instant overrideUntil) {
        return new NewsCollectResult(executionId, sourceId, discoveredCount, acceptedCount, duplicateCount,
            rejectedCount, failedCount, errorMessage, allowed, status, url, overrideReason, overrideUntil);
    }
}
