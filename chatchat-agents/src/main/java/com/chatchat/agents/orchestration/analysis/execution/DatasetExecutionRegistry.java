package com.chatchat.agents.orchestration.analysis.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-execution registry and deterministic synthesis barrier for all expected datasets. */
public final class DatasetExecutionRegistry {
    private final Map<String, DatasetExecutionState> states = new LinkedHashMap<>();

    public synchronized void expect(String datasetId, String executionId, Integer sourceStepId,
                                    String sourceName, String rawResultRef) {
        if (states.containsKey(datasetId)) {
            throw new IllegalArgumentException("Duplicate dataset id: " + datasetId);
        }
        states.put(datasetId, new DatasetExecutionState(datasetId, executionId, sourceStepId,
            sourceName, DatasetAnalysisStatus.EXPECTED, rawResultRef,
            List.of(), List.of(), List.of(), false));
    }

    public synchronized void analyzing(String datasetId) {
        replace(datasetId, DatasetAnalysisStatus.ANALYZING, List.of(), List.of(), List.of(), false);
    }

    public synchronized void analyzed(String datasetId, List<String> findings,
                                      List<String> evidenceIds, boolean truncated) {
        replace(datasetId, truncated ? DatasetAnalysisStatus.TRUNCATED : DatasetAnalysisStatus.ANALYZED,
            findings, evidenceIds, List.of(), truncated);
    }

    public synchronized void failed(String datasetId, String error) {
        replace(datasetId, DatasetAnalysisStatus.FAILED, List.of(), List.of(),
            error == null || error.isBlank() ? List.of("dataset analysis failed") : List.of(error), false);
    }

    public synchronized void skipped(String datasetId, String reason) {
        replace(datasetId, DatasetAnalysisStatus.SKIPPED, List.of(), List.of(),
            reason == null || reason.isBlank() ? List.of("dataset analysis produced no finding")
                : List.of(reason), false);
    }

    public synchronized List<DatasetExecutionState> snapshot() {
        return List.copyOf(states.values());
    }

    public synchronized List<String> missingDatasetIds() {
        return states.values().stream().filter(state -> !state.status().terminal())
            .map(DatasetExecutionState::datasetId).toList();
    }

    public synchronized boolean synthesisReady() {
        return !states.isEmpty() && missingDatasetIds().isEmpty();
    }

    public synchronized Map<String, Object> snapshotMap() {
        List<DatasetExecutionState> values = snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasets", values.stream().map(DatasetExecutionState::toMap).toList());
        result.put("expectedCount", values.size());
        result.put("analyzedCount", values.stream().filter(value ->
            value.status() == DatasetAnalysisStatus.ANALYZED
                || value.status() == DatasetAnalysisStatus.TRUNCATED).count());
        result.put("failedCount", values.stream().filter(value ->
            value.status() == DatasetAnalysisStatus.FAILED).count());
        result.put("skippedCount", values.stream().filter(value ->
            value.status() == DatasetAnalysisStatus.SKIPPED).count());
        result.put("missingDatasetIds", new ArrayList<>(missingDatasetIds()));
        result.put("synthesisReady", synthesisReady());
        return Map.copyOf(result);
    }

    private void replace(String datasetId, DatasetAnalysisStatus status, List<String> findings,
                         List<String> evidenceIds, List<String> errors, boolean truncated) {
        DatasetExecutionState previous = states.get(datasetId);
        if (previous == null) throw new IllegalArgumentException("Unknown dataset id: " + datasetId);
        states.put(datasetId, new DatasetExecutionState(previous.datasetId(), previous.executionId(),
            previous.sourceStepId(), previous.sourceName(), status, previous.rawResultRef(),
            findings, evidenceIds, errors, truncated));
    }
}
