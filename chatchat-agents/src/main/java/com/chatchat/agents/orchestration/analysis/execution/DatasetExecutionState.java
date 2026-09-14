package com.chatchat.agents.orchestration.analysis.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable snapshot of a dataset's execution and evidence lineage. */
public record DatasetExecutionState(
    String datasetId,
    String executionId,
    Integer sourceStepId,
    String sourceName,
    DatasetAnalysisStatus status,
    String rawResultRef,
    List<String> findings,
    List<String> evidenceIds,
    List<String> errors,
    boolean truncated
) {
    public DatasetExecutionState {
        datasetId = required(datasetId, "datasetId");
        executionId = executionId == null ? "" : executionId;
        sourceName = sourceName == null ? "" : sourceName;
        status = status == null ? DatasetAnalysisStatus.EXPECTED : status;
        rawResultRef = rawResultRef == null ? "" : rawResultRef;
        findings = List.copyOf(findings == null ? List.of() : findings);
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        errors = List.copyOf(errors == null ? List.of() : errors);
        truncated = truncated || status == DatasetAnalysisStatus.TRUNCATED;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("datasetId", datasetId);
        value.put("executionId", executionId);
        value.put("sourceStepId", sourceStepId);
        value.put("sourceName", sourceName);
        value.put("status", status.name());
        value.put("rawResultRef", rawResultRef);
        value.put("findings", findings);
        value.put("evidenceIds", evidenceIds);
        value.put("errors", errors);
        value.put("truncated", truncated);
        return java.util.Collections.unmodifiableMap(value);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
