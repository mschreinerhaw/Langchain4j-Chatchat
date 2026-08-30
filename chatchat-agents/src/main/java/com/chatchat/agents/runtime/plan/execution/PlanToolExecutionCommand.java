package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;

/** Serializable, engine-neutral identity and payload for one plan-owned tool invocation. */
public record PlanToolExecutionCommand(
    String schemaVersion,
    String runId,
    String planExecutionScope,
    String workflowExecutionAttempt,
    Integer stepId,
    String invocationRole,
    String invocationFingerprint,
    String idempotencyKey,
    ToolRuntimeRequest request
) {
    public static final String SCHEMA_VERSION = "plan_tool_execution.v1";

    public PlanToolExecutionCommand {
        schemaVersion = text(schemaVersion, SCHEMA_VERSION);
        runId = text(runId, "unscoped");
        planExecutionScope = text(planExecutionScope, runId + "::attempt:0");
        workflowExecutionAttempt = text(workflowExecutionAttempt, "0");
        invocationRole = text(invocationRole, "PRIMARY");
        if (stepId == null) {
            throw new IllegalArgumentException("Plan tool step id is required");
        }
        if (invocationFingerprint == null || invocationFingerprint.isBlank()) {
            throw new IllegalArgumentException("Plan tool invocation fingerprint is required");
        }
        invocationFingerprint = invocationFingerprint.trim();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Plan tool idempotency key is required");
        }
        idempotencyKey = idempotencyKey.trim();
        if (request == null || request.getToolName() == null || request.getToolName().isBlank()) {
            throw new IllegalArgumentException("Plan tool runtime request and tool name are required");
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
