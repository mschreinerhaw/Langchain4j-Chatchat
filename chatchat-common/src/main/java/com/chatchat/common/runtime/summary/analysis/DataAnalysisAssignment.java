package com.chatchat.common.runtime.summary.analysis;

import com.chatchat.common.runtime.summary.model.ModelSummaryTask;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable enterprise contract describing exactly what one analysis participant may analyze.
 *
 * <p>The assignment deliberately contains no Worker/Driver flag. A Worker receives a
 * {@link DataAnalysisScope#DATASET} assignment; a coordinating participant receives the
 * summaries explicitly listed by an {@link DataAnalysisScope#ASSIGNED_DATASET_COLLECTION}
 * assignment. Neither participant is permitted to summarize unassigned evidence.</p>
 */
public record DataAnalysisAssignment(
    String schemaVersion,
    String assignmentId,
    String inputSha256,
    DataAnalysisScope scope,
    DataAnalysisIsolationScope isolationScope,
    String originalUserQuestion,
    List<String> inputReferences,
    Map<String, Object> analysisContext,
    long timeoutMs,
    int attempt
) implements ModelSummaryTask {

    public static final String SCHEMA_VERSION = "data_analysis_assignment.v1";

    public DataAnalysisAssignment {
        schemaVersion = SCHEMA_VERSION;
        assignmentId = required(assignmentId, "assignmentId");
        inputSha256 = required(inputSha256, "inputSha256");
        if (scope == null) throw new IllegalArgumentException("analysis scope is required");
        if (isolationScope == null) {
            throw new IllegalArgumentException("analysis isolation scope is required");
        }
        originalUserQuestion = required(originalUserQuestion, "originalUserQuestion");
        inputReferences = inputReferences == null ? List.of() : inputReferences.stream()
            .map(reference -> required(reference, "inputReference"))
            .distinct()
            .toList();
        if (inputReferences.isEmpty()) {
            throw new IllegalArgumentException("at least one assigned input is required");
        }
        if (scope == DataAnalysisScope.DATASET && inputReferences.size() != 1) {
            throw new IllegalArgumentException("DATASET scope requires exactly one assigned input");
        }
        if (scope == DataAnalysisScope.RELATED_DATASET_GROUP && inputReferences.size() < 2) {
            throw new IllegalArgumentException(
                "RELATED_DATASET_GROUP scope requires at least two assigned inputs");
        }
        analysisContext = immutableMap(analysisContext);
        timeoutMs = Math.max(1, timeoutMs);
        attempt = Math.max(1, attempt);
    }

    @Override
    public String taskId() {
        return assignmentId;
    }

    @Override
    public String idempotencyKey() {
        return assignmentId + ":" + inputSha256;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("assignmentId", assignmentId);
        value.put("idempotencyKey", idempotencyKey());
        value.put("inputSha256", inputSha256);
        value.put("scope", scope.name());
        value.put("isolationScope", isolationScope.toMap());
        value.put("originalUserQuestion", originalUserQuestion);
        value.put("inputReferences", inputReferences);
        value.put("analysisContext", analysisContext);
        value.put("timeoutMs", timeoutMs);
        value.put("attempt", attempt);
        value.put("executionSteps", DataAnalysisParticipant.EXECUTION_STEPS.stream()
            .map(Enum::name).toList());
        return Collections.unmodifiableMap(value);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(required(key, "analysisContext key"),
            immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(required(String.valueOf(key),
                "analysisContext key"), immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DataAnalysisAssignment::immutableValue).toList();
        }
        if (value instanceof java.util.Set<?> set) {
            java.util.Set<Object> copy = new java.util.LinkedHashSet<>();
            set.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }
}
