package com.chatchat.agents.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.tool-runtime")
public class ToolRuntimeProperties {

    private boolean enforceAllowedTools = true;
    private boolean enforceAuthentication = true;
    private int defaultMaxCallsPerMinute = 0;
    /** Per tenant/tool burst ceiling. Zero disables the QPS gate. */
    private int defaultMaxCallsPerSecond = 0;
    private int circuitBreakerFailureThreshold = 3;
    private int circuitBreakerOpenSeconds = 60;
    private int topToolLimit = 6;
    private long defaultToolTimeoutMs = 1_800_000;
    private int maxBatchCalls = 32;
    private int maxBatchPayloadBytes = 262_144;
    /** Maximum serialized result retained inline across Agent/task/event boundaries. */
    private int maxOutputBytes = 262_144;
    private int maxOutputPreviewChars = 16_000;
    private int defaultRetryAttempts = 3;
    private int executionCorePoolSize = 4;
    private int executionMaxPoolSize = 32;
    private int executionQueueCapacity = 256;
    /** Audit persistence must never extend a user-facing tool call indefinitely. */
    private long auditSinkTimeoutMs = 250;
    private int auditQueueCapacity = 256;
    private String defaultRuntimeLevel = "readonly";
    private Map<String, String> levelPolicy = new LinkedHashMap<>();

    public long safeDefaultToolTimeoutMs() {
        return Math.max(0L, defaultToolTimeoutMs);
    }

    public int safeExecutionCorePoolSize() {
        return Math.max(1, executionCorePoolSize);
    }

    public int safeExecutionMaxPoolSize() {
        return Math.max(safeExecutionCorePoolSize(), executionMaxPoolSize);
    }

    public int safeExecutionQueueCapacity() {
        return Math.max(1, executionQueueCapacity);
    }

    public long safeAuditSinkTimeoutMs() {
        return Math.max(10L, Math.min(5_000L, auditSinkTimeoutMs));
    }

    public int safeAuditQueueCapacity() {
        return Math.max(1, Math.min(10_000, auditQueueCapacity));
    }

    public int safeDefaultRetryAttempts() {
        return Math.max(0, Math.min(5, defaultRetryAttempts));
    }

    public int safeMaxBatchCalls() {
        return Math.max(1, Math.min(256, maxBatchCalls));
    }

    public int safeMaxBatchPayloadBytes() {
        return Math.max(1_024, Math.min(4_194_304, maxBatchPayloadBytes));
    }

    public int safeMaxOutputBytes() {
        return Math.max(16_384, Math.min(4_194_304, maxOutputBytes));
    }

    public int safeMaxOutputPreviewChars() {
        return Math.max(1_000, Math.min(64_000, maxOutputPreviewChars));
    }
}
