package com.chatchat.runtime.temporal.contract;

/** Query projection for one independently durable tool execution. */
public record TemporalToolExecutionSnapshot(
    String workflowId,
    String toolName,
    String idempotencyKey,
    String status,
    int maximumAttempts
) {
}
