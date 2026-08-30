package com.chatchat.common.runtime.summary.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-neutral Worker progress/heartbeat envelope delivered to a Driver. */
public record ModelSummaryProgress(
    String schemaVersion,
    String stage,
    String taskId,
    String workReference,
    int workIndex,
    int workCount,
    String workerId,
    long occurredAtEpochMs,
    Map<String, Object> details
) {
    public static final String SCHEMA_VERSION = "model_summary_progress.v1";

    public ModelSummaryProgress {
        schemaVersion = SCHEMA_VERSION;
        stage = text(stage, "UNKNOWN");
        taskId = text(taskId, "unknown-task");
        workReference = text(workReference, taskId);
        workIndex = Math.max(1, workIndex);
        workCount = Math.max(workIndex, workCount);
        workerId = text(workerId, "unknown-worker");
        occurredAtEpochMs = occurredAtEpochMs <= 0 ? System.currentTimeMillis() : occurredAtEpochMs;
        details = details == null || details.isEmpty()
            ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.putAll(details);
        value.put("schemaVersion", schemaVersion);
        value.put("stage", stage);
        value.put("taskId", taskId);
        value.put("workReference", workReference);
        value.put("workIndex", workIndex);
        value.put("workCount", workCount);
        value.put("workerId", workerId);
        value.put("occurredAtEpochMs", occurredAtEpochMs);
        return Collections.unmodifiableMap(value);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
