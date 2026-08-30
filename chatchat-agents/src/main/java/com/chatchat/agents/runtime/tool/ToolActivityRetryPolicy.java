package com.chatchat.agents.runtime.tool;

import com.chatchat.common.tool.ToolMetadata;

import java.util.Locale;
import java.util.Map;

/** Fail-closed retry admission for independently durable tool Activities. */
public final class ToolActivityRetryPolicy {

    public static final String RETRY_SAFE_METADATA = "workflowRetrySafe";
    public static final String MAXIMUM_ATTEMPTS_METADATA = "workflowActivityMaximumAttempts";

    public Decision resolve(ToolMetadata metadata) {
        if (metadata == null) {
            return Decision.singleAttempt("tool metadata is unavailable");
        }
        String operation = normalize(metadata.getOperationType());
        boolean readOnly = "read".equals(operation) || "readonly".equals(operation)
            || "read_only".equals(operation);
        Map<String, Object> attributes = metadata.getMetadata() == null
            ? Map.of() : metadata.getMetadata();
        boolean explicitlySafe = booleanValue(attributes.get(RETRY_SAFE_METADATA))
            || booleanValue(attributes.get("idempotent"));
        if (!readOnly) {
            return Decision.singleAttempt("write or side-effecting tools require one Activity attempt");
        }
        if (!explicitlySafe) {
            return Decision.singleAttempt("read-only tool has no explicit idempotency declaration");
        }
        int maximumAttempts = integerValue(attributes.get(MAXIMUM_ATTEMPTS_METADATA), 3);
        maximumAttempts = Math.max(1, Math.min(5, maximumAttempts));
        return new Decision(maximumAttempts > 1, maximumAttempts,
            "explicitly idempotent read-only tool");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool
            : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private int integerValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record Decision(boolean retrySafe, int maximumAttempts, String reason) {
        public static Decision singleAttempt(String reason) {
            return new Decision(false, 1, reason);
        }
    }
}
