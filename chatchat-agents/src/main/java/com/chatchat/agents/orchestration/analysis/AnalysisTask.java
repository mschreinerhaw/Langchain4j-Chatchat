package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisPosition;
import com.chatchat.common.runtime.summary.ModelSummaryTask;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializable Driver-to-Worker contract for one immutable evidence chunk. */
public record AnalysisTask(
    String schemaVersion,
    String taskId,
    String inputSha256,
    GovernanceIsolationScope isolationScope,
    String datasetReference,
    int datasetIndex,
    int datasetCount,
    int chunkIndex,
    int chunkCount,
    int recordFrom,
    int recordTo,
    int totalRecords,
    Map<String, Object> analysisContext,
    Map<String, Object> evidenceLocator,
    List<Map<String, Object>> records,
    String userObjective,
    long timeoutMs,
    int attempt
) implements ModelSummaryTask {
    public static final String SCHEMA_VERSION = "analysis_task.v1";

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
        chunkIndex = Math.max(1, chunkIndex);
        chunkCount = Math.max(chunkIndex, chunkCount);
        recordFrom = Math.max(1, recordFrom);
        recordTo = Math.max(recordFrom, recordTo);
        totalRecords = Math.max(recordTo, totalRecords);
        analysisContext = immutable(analysisContext);
        evidenceLocator = immutable(evidenceLocator);
        records = records == null ? List.of() : List.copyOf(records);
        if (records.isEmpty() && evidenceLocator.isEmpty()) {
            throw new IllegalArgumentException("records or evidenceLocator is required");
        }
        userObjective = userObjective == null ? "" : userObjective;
        timeoutMs = Math.max(1, timeoutMs);
        attempt = Math.max(1, attempt);
    }

    public String idempotencyKey() {
        return taskId + ":" + inputSha256;
    }

    public RuntimeAnalysisPosition position() {
        return new RuntimeAnalysisPosition(
            datasetReference, chunkIndex, chunkCount, recordFrom, recordTo, totalRecords);
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
        value.put("chunkIndex", chunkIndex);
        value.put("chunkCount", chunkCount);
        value.put("recordFrom", recordFrom);
        value.put("recordTo", recordTo);
        value.put("totalRecords", totalRecords);
        value.put("analysisContext", analysisContext);
        value.put("evidenceLocator", evidenceLocator);
        value.put("records", records);
        value.put("userObjective", userObjective);
        value.put("timeoutMs", timeoutMs);
        value.put("attempt", attempt);
        return Collections.unmodifiableMap(value);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null || source.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
