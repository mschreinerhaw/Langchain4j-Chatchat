package com.chatchat.agents.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.agent-runtime")
public class AgentRuntimeProperties {

    private static final long DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    private int corePoolSize = 4;
    private int maxPoolSize = 16;
    private int queueCapacity = 100;
    private int keepAliveSeconds = 60;
    private String threadNamePrefix = "agent-runtime-";
    private int maxStoredRuns = 10_000;
    private long terminalRunTtlMs = DEFAULT_RETENTION_MS;
    private boolean cleanupEnabled = true;
    private long cleanupIntervalMs = 60L * 60 * 1000;
    private String storeType = "rocksdb";
    private String rocksDbPath = "./data/agent-runtime-rocksdb";
    private boolean rocksDbCreateIfMissing = true;
    private boolean failInterruptedRunsOnStartup = true;
    private int maxJsonStringLength = 100_000_000;
    private boolean evidenceExternalizationEnabled = true;
    private int evidenceExternalizationThresholdBytes = 262_144;
    /** Allows the final summary model to request one bounded internal web-retrieval enhancement round. */
    private boolean finalSummaryWebSearchEnabled = true;
    private int finalSummaryWebSearchMaxKeywords = 2;
    private int finalSummaryWebSearchResultLimit = 6;
    private int finalSummaryWebSearchEvidenceMaxChars = 16_000;
    private long finalSummaryWebSearchTimeoutMs = 45_000;

    public int corePoolSize() {
        return Math.max(1, corePoolSize);
    }

    public int maxPoolSize() {
        return Math.max(corePoolSize(), maxPoolSize);
    }

    public int queueCapacity() {
        return Math.max(1, queueCapacity);
    }

    public int keepAliveSeconds() {
        return Math.max(1, keepAliveSeconds);
    }

    public String threadNamePrefix() {
        return threadNamePrefix == null || threadNamePrefix.isBlank()
            ? "agent-runtime-"
            : threadNamePrefix;
    }

    public int maxStoredRuns() {
        return Math.max(1, maxStoredRuns);
    }

    public long terminalRunTtlMs() {
        return Math.max(0, terminalRunTtlMs);
    }

    public long cleanupIntervalMs() {
        return Math.max(60_000L, cleanupIntervalMs);
    }

    public String storeType() {
        return storeType == null || storeType.isBlank() ? "rocksdb" : storeType.trim();
    }

    public String rocksDbPath() {
        return rocksDbPath == null || rocksDbPath.isBlank()
            ? "./data/agent-runtime-rocksdb"
            : rocksDbPath.trim();
    }

    public int evidenceExternalizationThresholdBytes() {
        return Math.max(16_384, evidenceExternalizationThresholdBytes);
    }

    public int finalSummaryWebSearchMaxKeywords() {
        return Math.max(1, Math.min(3, finalSummaryWebSearchMaxKeywords));
    }

    public int finalSummaryWebSearchResultLimit() {
        return Math.max(1, Math.min(20, finalSummaryWebSearchResultLimit));
    }

    public int finalSummaryWebSearchEvidenceMaxChars() {
        return Math.max(2_000, Math.min(64_000, finalSummaryWebSearchEvidenceMaxChars));
    }

    public long finalSummaryWebSearchTimeoutMs() {
        return Math.max(5_000L, finalSummaryWebSearchTimeoutMs);
    }
}
