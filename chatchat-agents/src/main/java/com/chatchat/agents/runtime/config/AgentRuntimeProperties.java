package com.chatchat.agents.runtime.config;

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
    /** Maximum delegated/running Agent runs for one tenant. */
    private int maxConcurrentPerTenant = 4;
    /** Bounded waiting room per tenant; overflow is rejected as backpressure. */
    private int maxQueuedPerTenant = 50;
    private boolean tenantFairSchedulingEnabled = true;
    private int keepAliveSeconds = 60;
    private String threadNamePrefix = "agent-runtime-";
    private int maxStoredRuns = 10_000;
    private long terminalRunTtlMs = DEFAULT_RETENTION_MS;
    private boolean cleanupEnabled = true;
    private long cleanupIntervalMs = 60L * 60 * 1000;
    private String storeType = "database";
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
    private int evidenceCompletionMaxRounds = 3;
    /** Enables the business-neutral Answer Contract, evidence gate, critic and targeted repair loop. */
    private boolean answerQualityPipelineEnabled = true;
    private boolean answerCriticEnabled = true;
    private boolean answerRepairEnabled = true;
    private long answerCriticTimeoutMs = 45_000;
    /** Governs lossless analysis chunk boundaries only; it never truncates returned evidence. */
    private int recordAnalysisChunkMaxChars = 12_000;
    /** Governs lossless analysis chunk boundaries only; every returned record remains covered. */
    private int recordAnalysisChunkMaxRows = 50;
    /** Maximum model workers used to analyze independent datasets in parallel. */
    private int analysisSummaryWorkerCount = 4;
    /** Spark-style retries after the initial attempt for one failed dataset chunk. */
    private int analysisSummaryWorkerMaxRetries = 3;
    /** Worker heartbeat cadence; independent of blocking model inference. */
    private long analysisSummaryWorkerHeartbeatIntervalMs = 10_000;
    /** Remote Worker lease window. Missing heartbeats mean unreachable, not model failure. */
    private long analysisSummaryWorkerHeartbeatTimeoutMs = 30_000;
    /** Spills oversized loop-analysis mirrors outside the JVM without truncating source evidence. */
    private boolean analysisSpillEnabled = true;
    /** Must be different from rocksDbPath because RocksDB does not allow two independent handles on one path. */
    private String analysisSpillRocksDbPath = "./data/agent-analysis-spill-rocksdb";
    private boolean analysisSpillRocksDbCreateIfMissing = true;
    /** A single unusually large chunk also spills even when the dataset has only one record. */
    private int analysisSpillThresholdBytes = 65_536;
    /** Spill payloads and summary checkpoints remain recoverable for this duration. */
    private long analysisSpillTtlMs = DEFAULT_RETENTION_MS;
    /** Hard estimated model-token ceiling per execution; zero disables the global ceiling. */
    private long modelTokenBudget = 0;
    /** Hard estimated model-cost ceiling per execution; zero disables the global ceiling. */
    private double modelCostBudget = 0D;
    private double modelInputCostPerThousandTokens = 0D;
    private double modelOutputCostPerThousandTokens = 0D;
    private double budgetAlertRatio = 0.8D;

    public int corePoolSize() {
        return Math.max(1, corePoolSize);
    }

    public int maxPoolSize() {
        return Math.max(corePoolSize(), maxPoolSize);
    }

    public int queueCapacity() {
        return Math.max(1, queueCapacity);
    }

    public int maxConcurrentPerTenant() {
        return Math.max(1, maxConcurrentPerTenant);
    }

    public int maxQueuedPerTenant() {
        return Math.max(1, Math.min(queueCapacity(), maxQueuedPerTenant));
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
        return storeType == null || storeType.isBlank() ? "database" : storeType.trim();
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

    public int evidenceCompletionMaxRounds() {
        return Math.max(1, Math.min(5, evidenceCompletionMaxRounds));
    }

    public long answerCriticTimeoutMs() {
        return Math.max(5_000L, answerCriticTimeoutMs);
    }

    public int recordAnalysisChunkMaxChars() {
        return Math.max(1_000, recordAnalysisChunkMaxChars);
    }

    public int recordAnalysisChunkMaxRows() {
        return Math.max(1, recordAnalysisChunkMaxRows);
    }

    public int analysisSummaryWorkerCount() {
        return Math.max(1, Math.min(16, analysisSummaryWorkerCount));
    }

    public int analysisSummaryWorkerMaxRetries() {
        return Math.max(0, Math.min(9, analysisSummaryWorkerMaxRetries));
    }

    public long analysisSummaryWorkerHeartbeatIntervalMs() {
        return Math.max(250L, analysisSummaryWorkerHeartbeatIntervalMs);
    }

    public long analysisSummaryWorkerHeartbeatTimeoutMs() {
        return Math.max(analysisSummaryWorkerHeartbeatIntervalMs() * 2,
            analysisSummaryWorkerHeartbeatTimeoutMs);
    }

    public String analysisSpillRocksDbPath() {
        return analysisSpillRocksDbPath == null || analysisSpillRocksDbPath.isBlank()
            ? "./data/agent-analysis-spill-rocksdb"
            : analysisSpillRocksDbPath.trim();
    }

    public int analysisSpillThresholdBytes() {
        return Math.max(1_024, analysisSpillThresholdBytes);
    }

    public long analysisSpillTtlMs() {
        return Math.max(0, analysisSpillTtlMs);
    }

    public long modelTokenBudget() {
        return Math.max(0, modelTokenBudget);
    }

    public double modelCostBudget() {
        return Math.max(0D, modelCostBudget);
    }

    public double modelInputCostPerThousandTokens() {
        return Math.max(0D, modelInputCostPerThousandTokens);
    }

    public double modelOutputCostPerThousandTokens() {
        return Math.max(0D, modelOutputCostPerThousandTokens);
    }

    public double budgetAlertRatio() {
        return Math.max(0.01D, Math.min(1D, budgetAlertRatio));
    }
}
