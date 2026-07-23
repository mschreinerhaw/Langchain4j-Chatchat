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
    private int circuitBreakerFailureThreshold = 3;
    private int circuitBreakerOpenSeconds = 60;
    private int topToolLimit = 6;
    private long defaultToolTimeoutMs = 60_000;
    private int defaultRetryAttempts = 3;
    private int executionCorePoolSize = 4;
    private int executionMaxPoolSize = 32;
    private int executionQueueCapacity = 256;
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

    public int safeDefaultRetryAttempts() {
        return Math.max(0, Math.min(5, defaultRetryAttempts));
    }
}
