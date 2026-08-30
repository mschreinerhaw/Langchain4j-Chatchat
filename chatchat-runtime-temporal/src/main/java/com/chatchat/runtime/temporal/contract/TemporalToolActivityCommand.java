package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolActivityRetryPolicy;
import com.chatchat.common.tool.ToolMetadata;

/** Serializable contract for one independently durable tool invocation. */
public record TemporalToolActivityCommand(
    ToolRuntimeRequest request,
    String idempotencyKey,
    int maximumAttempts,
    long startToCloseSeconds,
    boolean retrySafe,
    String retryPolicyReason
) {
    public TemporalToolActivityCommand {
        if (request == null || request.getToolName() == null || request.getToolName().isBlank()) {
            throw new IllegalArgumentException("Tool Activity request and tool name are required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Tool Activity idempotency key is required");
        }
        idempotencyKey = idempotencyKey.trim();
        maximumAttempts = retrySafe ? Math.max(1, Math.min(5, maximumAttempts)) : 1;
        startToCloseSeconds = Math.max(1L, startToCloseSeconds);
        retryPolicyReason = retryPolicyReason == null || retryPolicyReason.isBlank()
            ? "retry policy was not supplied" : retryPolicyReason.trim();
    }

    public static TemporalToolActivityCommand governed(
        ToolRuntimeRequest request,
        ToolMetadata metadata,
        String idempotencyKey,
        long startToCloseSeconds
    ) {
        ToolActivityRetryPolicy.Decision decision = new ToolActivityRetryPolicy().resolve(metadata);
        return new TemporalToolActivityCommand(
            request, idempotencyKey, decision.maximumAttempts(), startToCloseSeconds,
            decision.retrySafe(), decision.reason());
    }
}
