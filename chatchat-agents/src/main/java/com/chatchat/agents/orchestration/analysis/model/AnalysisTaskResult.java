package com.chatchat.agents.orchestration.analysis.model;

import com.chatchat.common.runtime.summary.ModelSummaryTaskResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serializable Worker-to-Driver result contract. */
public record AnalysisTaskResult(
    String schemaVersion,
    String taskId,
    String inputSha256,
    String workerId,
    String status,
    long durationMs,
    int attempt,
    AnalysisDatasetSummary summary,
    String error
) implements ModelSummaryTaskResult<AnalysisDatasetSummary> {
    public static final String SCHEMA_VERSION = "analysis_dataset_task_result.v1";

    public AnalysisTaskResult {
        schemaVersion = SCHEMA_VERSION;
        taskId = taskId == null ? "" : taskId;
        inputSha256 = inputSha256 == null ? "" : inputSha256;
        workerId = workerId == null ? "unknown-worker" : workerId;
        status = status == null ? "FAILED" : status;
        durationMs = Math.max(0, durationMs);
        attempt = Math.max(1, attempt);
        error = error == null ? "" : error;
    }

    public static AnalysisTaskResult completed(
        AnalysisTask task, String workerId, AnalysisDatasetSummary summary, long durationMs
    ) {
        String status = summary != null && "FALLBACK".equals(summary.outcome())
            ? "FALLBACK"
            : "SUCCESS";
        return new AnalysisTaskResult(SCHEMA_VERSION, task.taskId(), task.inputSha256(),
            workerId, status, durationMs, task.attempt(), summary, "");
    }

    public static AnalysisTaskResult failed(
        AnalysisTask task, String workerId, long durationMs, Throwable failure
    ) {
        return new AnalysisTaskResult(SCHEMA_VERSION, task.taskId(), task.inputSha256(),
            workerId, "FAILED", durationMs, task.attempt(), null,
            failure == null ? "unknown worker failure" : String.valueOf(failure.getMessage()));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("taskId", taskId);
        value.put("inputSha256", inputSha256);
        value.put("workerId", workerId);
        value.put("status", status);
        value.put("durationMs", durationMs);
        value.put("attempt", attempt);
        value.put("summary", summary == null ? Map.of() : summary.toMap());
        value.put("error", error);
        return Collections.unmodifiableMap(value);
    }
}
