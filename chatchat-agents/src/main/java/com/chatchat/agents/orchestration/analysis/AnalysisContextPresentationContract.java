package com.chatchat.agents.orchestration.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a compact, source-neutral presentation view from governed analysis metadata.
 * Business meaning remains configuration data; this class never recognizes concrete domains or fields.
 */
public final class AnalysisContextPresentationContract {

    public static final String VERSION = "analysis_context_presentation.v1";

    private AnalysisContextPresentationContract() {
    }

    public static Map<String, Object> semanticView(String datasetReference, Map<String, Object> context) {
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        Map<String, Object> source = map(safeContext.get("source"));
        Map<String, Object> schema = map(safeContext.get("schema"));

        Map<String, Object> dataset = new LinkedHashMap<>();
        put(dataset, "technicalReference", datasetReference);
        put(dataset, "displayName", first(source, "displayName", "title", "name"));
        put(dataset, "purpose", first(source, "description", "purpose"));

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> field : fieldMaps(schema.get("fields"))) {
            String technicalName = first(field, "technicalName", "name", "field", "key");
            if (technicalName == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("technicalName", technicalName);
            put(item, "displayName", first(field, "label", "title", "displayName", "description", "comment"));
            put(item, "description", first(field, "description", "comment"));
            put(item, "type", first(field, "type", "dataType"));
            put(item, "unit", first(field, "unit"));
            fields.add(Collections.unmodifiableMap(item));
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaVersion", VERSION);
        view.put("dataset", Collections.unmodifiableMap(dataset));
        view.put("fields", List.copyOf(fields));
        return Collections.unmodifiableMap(view);
    }

    public static String synthesisInstruction() {
        return "- Semantic presentation contract (" + VERSION + "): use source.displayName and "
            + "source.description as the dataset's business name and purpose. When schema.fields supplies "
            + "label, title, displayName, description, or comment for a technical field, render its first mention "
            + "and table header as 'business meaning (technicalName)'. Never show an opaque technical key alone "
            + "when authoritative display metadata exists. Keep the exact technical key for traceability, never "
            + "invent missing meaning, and add a compact field dictionary only when it materially helps reading.\n"
            + "- Deterministic insight presentation: executed findings are authoritative calculations, but execution "
            + "does not by itself make a finding relevant to the user's answer. Select placement from the user's goal "
            + "and deliverables plus finding.details.presentation. ALWAYS may enter the conclusion when material; "
            + "WHEN_RELEVANT enters only when it answers a requested dimension; EXCEPTION_ONLY enters only for an "
            + "emitted exception; SUPPORTING stays outside the conclusion unless directly requested. Respect "
            + "conclusionEligible, priority, section, and relevanceHint. Never infer relevance from an operator name "
            + "alone. Do not recalculate or rename selected findings, and explain them only from supplied semantic "
            + "metadata and evidence; do not add domain knowledge.\n";
    }

    private static List<Map<String, Object>> fieldMaps(Object value) {
        if (value instanceof List<?> values) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : values) {
                Map<String, Object> field = map(item);
                if (!field.isEmpty()) result.add(field);
            }
            return result;
        }
        Map<String, Object> keyed = map(value);
        if (keyed.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        keyed.forEach((name, definition) -> {
            Map<String, Object> field = new LinkedHashMap<>(map(definition));
            field.putIfAbsent("technicalName", name);
            result.add(field);
        });
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private static String first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return null;
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
