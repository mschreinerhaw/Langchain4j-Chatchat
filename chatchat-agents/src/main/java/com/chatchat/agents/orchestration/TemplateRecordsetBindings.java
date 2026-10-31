package com.chatchat.agents.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** References to complete, successful, authorized recordsets from this Runtime run. */
final class TemplateRecordsetBindings {
    static final String OUTPUTS = "completedStepOutputs";

    record Rows(int stepId, String path, List<?> value, Object columns) { }

    static List<Rows> available(Map<String, Object> context) {
        List<Rows> rows = new ArrayList<>();
        if (context.get(OUTPUTS) instanceof Map<?, ?> outputs) {
            outputs.forEach((id, output) -> {
                try { collect(Integer.parseInt(String.valueOf(id)), "$", output, 0, rows); }
                catch (NumberFormatException ignored) { /* Not a Runtime step id. */ }
            });
        }
        return List.copyOf(rows);
    }

    private static void collect(int id, String path, Object output, int depth, List<Rows> found) {
        if (depth > 10 || !(output instanceof Map<?, ?> map)) return;
        if (List.of("FAILED", "FAILURE", "ERROR", "DENIED", "CANCELLED").contains(
            String.valueOf(map.get("status")).toUpperCase(java.util.Locale.ROOT))) return;
        if (map.get("rows") instanceof List<?> rows && map.get("columns") instanceof List<?>
            && Boolean.TRUE.equals(map.get("complete")) && Boolean.FALSE.equals(map.get("possiblyTruncated"))) {
            found.add(new Rows(id, path + ".rows", rows, map.get("columns")));
            return;
        }
        // Never offer transport metadata or raw duplicate envelopes as business parameters.
        for (String key : List.of("data", "result", "output")) {
            if (map.containsKey(key)) collect(id, path + "." + key, map.get(key), depth + 1, found);
        }
    }

    static Map<String, Object> modelContext(Map<String, Object> context) {
        Map<String, Object> view = new LinkedHashMap<>(context);
        view.remove(OUTPUTS);
        view.put("completedRecordsets", available(context).stream().map(rows -> Map.of(
            "stepId", rows.stepId(), "outputPath", rows.path(), "rowCount", rows.value().size(),
            "columns", rows.columns(), "preview", rows.value().subList(0, Math.min(2, rows.value().size())))).toList());
        return view;
    }

    static Rows resolve(Map<String, Object> context, Object reference) {
        if (!(reference instanceof Map<?, ?> ref) || !(ref.get("stepId") instanceof Number id)
            || id.doubleValue() != id.intValue() || !(ref.get("outputPath") instanceof String path)) return null;
        return available(context).stream()
            .filter(rows -> rows.stepId() == id.intValue() && Objects.equals(rows.path(), path))
            .findFirst().orElse(null);
    }
}
