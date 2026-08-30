package com.chatchat.agents.runtime.workflow;

public record WorkflowExecutionSnapshot(
    String workflowId,
    String workflowType,
    String tenantId,
    String idempotencyKey,
    WorkflowExecutionStatus status,
    int attempt,
    long startedAt,
    Long finishedAt,
    String cancellationReason,
    String errorMessage
) {
}
