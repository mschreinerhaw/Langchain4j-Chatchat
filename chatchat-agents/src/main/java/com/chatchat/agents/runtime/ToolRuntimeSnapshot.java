package com.chatchat.agents.runtime;

import java.util.List;

public record ToolRuntimeSnapshot(
    long totalCalls,
    long successCalls,
    long failedCalls,
    long deniedCalls,
    long rateLimitedCalls,
    long circuitOpenRejects,
    long activeCalls,
    long openCircuits,
    List<ToolMetric> topTools
) {

    public record ToolMetric(
        String toolName,
        long totalCalls,
        long successCalls,
        long failedCalls,
        long deniedCalls,
        long rateLimitedCalls,
        long circuitOpenRejects,
        long activeCalls,
        long averageDurationMs,
        long lastDurationMs
    ) {
    }
}
