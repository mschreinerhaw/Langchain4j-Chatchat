package com.chatchat.agents.orchestration.analysis.model;

import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.ModelSummaryTask;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializable Driver-to-Worker contract for one complete immutable dataset. */
public record AnalysisTask(
    String schemaVersion,
    String taskId,
    String inputSha256,
    GovernanceIsolationScope isolationScope,
    String datasetReference,
    int datasetIndex,
    int datasetCount,
    Map<String, Object> analysisContext,
    Map<String, Object> evidenceLocator,
    List<Map<String, Object>> records,
    String userObjective,
    int maximumChunkRows,
    int maximumChunkChars,
    int spillThresholdBytes,
    int maximumRetries,
    long timeoutMs,
    int attempt
) implements ModelSummaryTask {
    public static final String SCHEMA_VERSION = "analysis_dataset_task.v1";

    public AnalysisTask {
        schemaVersion = SCHEMA_VERSION;
        taskId = required(taskId, "taskId");
        inputSha256 = required(inputSha256, "inputSha256");
        isolationScope = isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null)
            : isolationScope;
        datasetReference = required(datasetReference, "datasetReference");
        datasetIndex = Math.max(1, datasetIndex);
        datasetCount = Math.max(datasetIndex, datasetCount);
        analysisContext = immutable(analysisContext);
        evidenceLocator = immutable(evidenceLocator);
        records = records == null ? List.of() : List.copyOf(records);
        if (records.isEmpty() && evidenceLocator.isEmpty()) {
            throw new IllegalArgumentException("records or evidenceLocator is required");
        }
        userObjective = required(userObjective, "originalUserQuestion");
        maximumChunkRows = Math.max(1, maximumChunkRows);
        maximumChunkChars = Math.max(1_000, maximumChunkChars);
        spillThresholdBytes = Math.max(1_000, spillThresholdBytes);
        maximumRetries = Math.max(0, maximumRetries);
        timeoutMs = Math.max(1, timeoutMs);
        attempt = Math.max(1, attempt);
    }

    @Override
    public String idempotencyKey() {
        return taskId + ":" + inputSha256;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("taskId", taskId);
        value.put("idempotencyKey", idempotencyKey());
        value.put("inputSha256", inputSha256);
        value.put("isolationScope", isolationScope.toMap());
        value.put("datasetReference", datasetReference);
        value.put("datasetIndex", datasetIndex);
        value.put("datasetCount", datasetCount);
        value.put("analysisContext", analysisContext);
        value.put("evidenceLocator", evidenceLocator);
        value.put("records", records);
        value.put("userObjective", userObjective);
        value.put("originalUserQuestion", originalUserQuestion());
        value.put("maximumChunkRows", maximumChunkRows);
        value.put("maximumChunkChars", maximumChunkChars);
        value.put("spillThresholdBytes", spillThresholdBytes);
        value.put("maximumRetries", maximumRetries);
        value.put("maximumAttempts", maximumAttempts());
        // Model inference uses the system model/request deadline. This value is only the
        // Driver-to-Worker heartbeat lease for transports that need stale-owner detection.
        value.put("timeoutMs", timeoutMs);
        value.put("heartbeatTimeoutMs", timeoutMs);
        value.put("modelTimeoutPolicy", "SYSTEM_MODEL_REQUEST_TIMEOUT");
        value.put("attempt", attempt);
        return Collections.unmodifiableMap(value);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    public int maximumAttempts() {
        return maximumRetries + 1;
    }

    /** Authoritative, unmodified user intent carried from Driver to every Worker stage. */
    public String originalUserQuestion() {
        return userObjective;
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null || source.isEmpty()
            ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
