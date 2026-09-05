package com.chatchat.agents.runtime.analysis;

import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter.AnalysisDataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless structural projection of JSON stdout; paths identify data, never imply joins. */
final class PythonStdoutRecordProjector {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    List<AnalysisDataset> project(String reference, Object data) {
        if (!(data instanceof Map<?, ?> bridge)
            || !"python_analysis_bridge_result.v1".equals(bridge.get("schemaVersion"))) return List.of();
        Object stdout = bridge.get("stdout");
        try {
            if (stdout instanceof String text) stdout = mapper.readValue(text, Object.class);
        } catch (Exception malformedJson) {
            return List.of(); // The existing lossless payload path handles text and malformed output.
        }
        if (!(stdout instanceof Map<?, ?>) && !objectArray(stdout)) return List.of();
        List<AnalysisDataset> datasets = new ArrayList<>();
        visit(reference, "$.data.stdout", stdout, datasets, 0);
        List<Map<String, Object>> records = new ArrayList<>();
        for (AnalysisDataset dataset : datasets) {
            String path = String.valueOf(dataset.analysisContext().get("canonicalPath"));
            for (int index = 0; index < dataset.records().size(); index++)
                records.add(Map.of("sourcePath", path, "recordIndex", index,
                    "values", dataset.records().get(index)));
        }
        // Keep one assignment: splitting each statistics object into a Worker would multiply model calls.
        return List.of(new AnalysisDataset(reference + "#stdout", Map.of(
            "projectionMode", "PYTHON_JSON_STDOUT_RECORDS", "canonicalPath", "$.data.stdout",
            "projectionSource", "governed_data", "sourceGrain", "PATH_SCOPED_JSON_OBJECT",
            "relationshipPolicy", "NO_IMPLICIT_JOIN_OR_AGGREGATION"), List.copyOf(records)));
    }

    private void visit(String reference, String path, Object value, List<AnalysisDataset> datasets, int depth) {
        if (depth >= 24) {
            datasets.add(dataset(reference, path, List.of(Map.of("value", value))));
            return;
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> scalarFields = new LinkedHashMap<>();
            List<Map.Entry<?, ?>> nested = new ArrayList<>();
            for (var field : object.entrySet()) {
                Object child = field.getValue();
                if (child instanceof Map<?, ?> map && !map.isEmpty() || objectArray(child)) nested.add(field);
                else scalarFields.put(String.valueOf(field.getKey()), child);
            }
            if (!scalarFields.isEmpty() || object.isEmpty())
                datasets.add(dataset(reference, path, List.of(Collections.unmodifiableMap(scalarFields))));
            for (var field : nested)
                visit(reference, path + "[" + quoted(String.valueOf(field.getKey())) + "]", field.getValue(), datasets, depth + 1);
        } else if (value instanceof List<?> array) {
            boolean flat = array.stream().map(item -> (Map<?, ?>) item).flatMap(map -> map.values().stream())
                .noneMatch(child -> child instanceof Map<?, ?> map && !map.isEmpty() || objectArray(child));
            if (flat) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object item : array) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    ((Map<?, ?>) item).forEach((key, child) -> row.put(String.valueOf(key), child));
                    rows.add(Collections.unmodifiableMap(row));
                }
                datasets.add(dataset(reference, path, rows));
            } else {
                for (int index = 0; index < array.size(); index++)
                    visit(reference, path + "[" + index + "]", array.get(index), datasets, depth + 1);
            }
        }
    }

    private AnalysisDataset dataset(String reference, String path, List<Map<String, Object>> rows) {
        return new AnalysisDataset(reference + "#" + path, Map.of(
            "projectionMode", "PYTHON_JSON_STDOUT_RECORDS", "canonicalPath", path,
            "projectionSource", "governed_data", "sourceGrain", "JSON_OBJECT",
            "relationshipPolicy", "NO_IMPLICIT_JOIN_OR_AGGREGATION"), List.copyOf(rows));
    }

    private boolean objectArray(Object value) {
        return value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(Map.class::isInstance);
    }

    private String quoted(String key) {
        try { return mapper.writeValueAsString(key); }
        catch (Exception impossible) { throw new IllegalArgumentException("Invalid JSON field", impossible); }
    }
}
