package com.chatchat.agents.orchestration.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serializable Worker progress envelope delivered to the Driver event stream. */
public record AnalysisTaskProgress(
    String schemaVersion,
    String stage,
    String taskId,
    String datasetReference,
    int datasetIndex,
    int datasetCount,
    String workerId,
    Map<String, Object> details
) {
    public static final String SCHEMA_VERSION = "analysis_task_progress.v1";

    public AnalysisTaskProgress {
        schemaVersion = SCHEMA_VERSION;
        stage = stage == null ? "UNKNOWN" : stage;
        taskId = taskId == null ? "" : taskId;
        datasetReference = datasetReference == null ? "" : datasetReference;
        datasetIndex = Math.max(1, datasetIndex);
        datasetCount = Math.max(datasetIndex, datasetCount);
        workerId = workerId == null ? "unknown-worker" : workerId;
        details = details == null || details.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("stage", stage);
        value.put("taskId", taskId);
        value.put("datasetReference", datasetReference);
        value.put("datasetIndex", datasetIndex);
        value.put("datasetCount", datasetCount);
        value.put("workerId", workerId);
        value.putAll(details);
        return Collections.unmodifiableMap(value);
    }
}
