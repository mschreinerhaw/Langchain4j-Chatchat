package com.chatchat.agents.runtime.workflow;

public record WorkflowStartRequest<I>(
    String workflowId,
    String workflowType,
    String tenantId,
    String idempotencyKey,
    I input
) {
    public WorkflowStartRequest {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("Workflow id is required");
        }
        if (workflowType == null || workflowType.isBlank()) {
            throw new IllegalArgumentException("Workflow type is required");
        }
        workflowId = workflowId.trim();
        workflowType = workflowType.trim();
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
            ? workflowId
            : idempotencyKey.trim();
    }
}
