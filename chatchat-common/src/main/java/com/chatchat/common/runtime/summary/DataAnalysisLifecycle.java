package com.chatchat.common.runtime.summary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral, immutable gate for the four mandatory stages of human-style data analysis.
 * A stage cannot be skipped and final synthesis cannot start before every dispatched dataset has
 * a terminal Worker result.
 */
public record DataAnalysisLifecycle(
    String schemaVersion,
    String analysisId,
    Stage stage,
    int datasetCount,
    int relationshipGroupCount,
    int relationshipEdgeCount,
    int dispatchedTaskCount,
    int terminalTaskCount,
    int successfulTaskCount,
    int failedTaskCount,
    int finalInputCount,
    List<Stage> completedStages
) {
    public static final String SCHEMA_VERSION = "data_analysis_lifecycle.v1";

    public enum Stage {
        NOT_STARTED,
        RELATIONSHIPS_ESTABLISHED,
        DATASETS_DISPATCHED,
        WORKERS_RECONCILED,
        FINAL_SUMMARY_COMPLETED
    }

    public DataAnalysisLifecycle {
        schemaVersion = SCHEMA_VERSION;
        analysisId = required(analysisId, "analysisId");
        stage = stage == null ? Stage.NOT_STARTED : stage;
        if (datasetCount < 1) throw new IllegalArgumentException("datasetCount must be positive");
        relationshipGroupCount = nonNegative(relationshipGroupCount, "relationshipGroupCount");
        relationshipEdgeCount = nonNegative(relationshipEdgeCount, "relationshipEdgeCount");
        dispatchedTaskCount = nonNegative(dispatchedTaskCount, "dispatchedTaskCount");
        terminalTaskCount = nonNegative(terminalTaskCount, "terminalTaskCount");
        successfulTaskCount = nonNegative(successfulTaskCount, "successfulTaskCount");
        failedTaskCount = nonNegative(failedTaskCount, "failedTaskCount");
        finalInputCount = nonNegative(finalInputCount, "finalInputCount");
        completedStages = completedStages == null ? List.of() : List.copyOf(completedStages);
        validateSnapshot(stage, datasetCount, relationshipGroupCount, dispatchedTaskCount,
            terminalTaskCount, successfulTaskCount, failedTaskCount, finalInputCount,
            completedStages);
    }

    public static DataAnalysisLifecycle begin(String analysisId, int datasetCount) {
        return new DataAnalysisLifecycle(SCHEMA_VERSION, analysisId, Stage.NOT_STARTED,
            datasetCount, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    public DataAnalysisLifecycle relationshipsEstablished(int groupCount, int edgeCount) {
        requireStage(Stage.NOT_STARTED);
        if (groupCount < 1) throw new IllegalArgumentException("at least one relationship group is required");
        return next(Stage.RELATIONSHIPS_ESTABLISHED, groupCount, edgeCount,
            0, 0, 0, 0, 0);
    }

    public DataAnalysisLifecycle datasetsDispatched(int taskCount) {
        requireStage(Stage.RELATIONSHIPS_ESTABLISHED);
        if (taskCount != datasetCount) {
            throw new IllegalStateException("every dataset must be dispatched exactly once: datasets="
                + datasetCount + ", tasks=" + taskCount);
        }
        return next(Stage.DATASETS_DISPATCHED, relationshipGroupCount, relationshipEdgeCount,
            taskCount, 0, 0, 0, 0);
    }

    public DataAnalysisLifecycle workersReconciled(int successfulCount, int failedCount) {
        requireStage(Stage.DATASETS_DISPATCHED);
        int terminalCount = nonNegative(successfulCount, "successfulCount")
            + nonNegative(failedCount, "failedCount");
        if (terminalCount != dispatchedTaskCount) {
            throw new IllegalStateException("worker reconciliation must account for every dispatched task: dispatched="
                + dispatchedTaskCount + ", terminal=" + terminalCount);
        }
        return next(Stage.WORKERS_RECONCILED, relationshipGroupCount, relationshipEdgeCount,
            dispatchedTaskCount, terminalCount, successfulCount, failedCount, 0);
    }

    public DataAnalysisLifecycle finalSummaryCompleted(int inputCount) {
        requireStage(Stage.WORKERS_RECONCILED);
        if (successfulTaskCount > 0 && inputCount < 1) {
            throw new IllegalStateException("successful Worker results require final synthesis inputs");
        }
        return next(Stage.FINAL_SUMMARY_COMPLETED, relationshipGroupCount, relationshipEdgeCount,
            dispatchedTaskCount, terminalTaskCount, successfulTaskCount, failedTaskCount,
            nonNegative(inputCount, "inputCount"));
    }

    public boolean complete() {
        return stage == Stage.FINAL_SUMMARY_COMPLETED;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("analysisId", analysisId);
        value.put("stage", stage.name());
        value.put("datasetCount", datasetCount);
        value.put("relationshipGroupCount", relationshipGroupCount);
        value.put("relationshipEdgeCount", relationshipEdgeCount);
        value.put("dispatchedTaskCount", dispatchedTaskCount);
        value.put("terminalTaskCount", terminalTaskCount);
        value.put("successfulTaskCount", successfulTaskCount);
        value.put("failedTaskCount", failedTaskCount);
        value.put("finalInputCount", finalInputCount);
        value.put("completedStages", completedStages.stream().map(Enum::name).toList());
        value.put("complete", complete());
        return Collections.unmodifiableMap(value);
    }

    private DataAnalysisLifecycle next(Stage nextStage, int groups, int edges, int dispatched,
                                       int terminal, int successful, int failed, int finalInputs) {
        List<Stage> stages = new java.util.ArrayList<>(completedStages);
        stages.add(nextStage);
        return new DataAnalysisLifecycle(SCHEMA_VERSION, analysisId, nextStage, datasetCount,
            groups, edges, dispatched, terminal, successful, failed, finalInputs, stages);
    }

    private void requireStage(Stage expected) {
        if (stage != expected) {
            throw new IllegalStateException("data analysis stage must be " + expected + " before advancing from " + stage);
        }
    }

    private static int nonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    private static void validateSnapshot(Stage stage, int datasets, int groups, int dispatched,
                                         int terminal, int successful, int failed, int finalInputs,
                                         List<Stage> completed) {
        List<Stage> ordered = List.of(Stage.RELATIONSHIPS_ESTABLISHED, Stage.DATASETS_DISPATCHED,
            Stage.WORKERS_RECONCILED, Stage.FINAL_SUMMARY_COMPLETED);
        int completedCount = stage == Stage.NOT_STARTED ? 0 : ordered.indexOf(stage) + 1;
        if (completedCount < 0 || !completed.equals(ordered.subList(0, completedCount))) {
            throw new IllegalArgumentException("completedStages must be the exact lifecycle prefix for " + stage);
        }
        if (completedCount >= 1 && groups < 1) {
            throw new IllegalArgumentException("relationship stage requires at least one group");
        }
        if (completedCount >= 2 && dispatched != datasets) {
            throw new IllegalArgumentException("dispatch stage must account for every dataset");
        }
        if (completedCount >= 3
            && (terminal != dispatched || successful + failed != terminal)) {
            throw new IllegalArgumentException("reconciliation stage must account for every dispatched task");
        }
        if (completedCount >= 4 && successful > 0 && finalInputs < 1) {
            throw new IllegalArgumentException("final stage requires inputs for successful Worker results");
        }
        if ((completedCount < 2 && dispatched != 0)
            || (completedCount < 3 && (terminal != 0 || successful != 0 || failed != 0))
            || (completedCount < 4 && finalInputs != 0)) {
            throw new IllegalArgumentException("future lifecycle stage counters must remain zero");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
