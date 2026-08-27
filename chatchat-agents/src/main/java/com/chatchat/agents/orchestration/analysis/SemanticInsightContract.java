package com.chatchat.agents.orchestration.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime-owned, source-neutral semantic and analysis contract. */
public record SemanticInsightContract(
    String schemaVersion,
    String tenantId,
    String contractId,
    String version,
    String status,
    String datasetAlias,
    List<Field> fields,
    List<Recipe> recipes
) {
    public static final String SCHEMA_VERSION = "semantic_insight_contract.v1";

    public SemanticInsightContract {
        schemaVersion = SCHEMA_VERSION;
        fields = fields == null ? List.of() : List.copyOf(fields);
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
    }

    /** Parses a contract loaded by a trusted persistence provider. */
    public static SemanticInsightContract fromMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return null;
        List<Field> fields = maps(value.get("fields")).stream().map(Field::from).toList();
        List<Recipe> recipes = maps(value.get("recipes")).stream().map(Recipe::from).toList();
        return new SemanticInsightContract(SCHEMA_VERSION, text(value.get("tenantId")),
            text(value.get("contractId")), text(value.get("version")), text(value.get("status")),
            text(value.get("datasetAlias")), fields, recipes);
    }

    public Map<String, Field> fieldsBySemantic() {
        Map<String, Field> result = new LinkedHashMap<>();
        fields.stream().filter(Field::valid).forEach(field -> result.put(field.semantic(), field));
        return Collections.unmodifiableMap(result);
    }

    public record Field(String field, String semantic, String label, String unit,
                        boolean sensitive, String aggregation) {
        static Field from(Map<String, Object> value) {
            return new Field(text(value.get("field")), text(value.get("semantic")),
                text(value.get("label")), text(value.get("unit")),
                truthy(value.get("sensitive")), text(value.get("aggregation")));
        }
        boolean valid() { return field != null && !field.isBlank() && semantic != null && !semantic.isBlank(); }
    }

    public record Recipe(String id, String operator, String label, Map<String, Object> parameters,
                         Presentation presentation) {
        public Recipe(String id, String operator, String label, Map<String, Object> parameters) {
            this(id, operator, label, parameters, Presentation.defaultPolicy());
        }

        public Recipe {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            presentation = presentation == null ? Presentation.defaultPolicy() : presentation;
        }

        static Recipe from(Map<String, Object> value) {
            Map<String, Object> parameters = new LinkedHashMap<>(value);
            parameters.keySet().removeAll(List.of(
                "id", "operator", "label", "presentationMode", "conclusionEligible",
                "presentationPriority", "section", "relevanceHint"));
            return new Recipe(text(value.get("id")), text(value.get("operator")),
                text(value.get("label")), Collections.unmodifiableMap(parameters),
                new Presentation(text(value.get("presentationMode")),
                    booleanValue(value.get("conclusionEligible"), true),
                    integer(value.get("presentationPriority"), 0), text(value.get("section")),
                    text(value.get("relevanceHint"))));
        }
    }

    /** Controls answer placement; it does not decide or alter the numeric result. */
    public record Presentation(String mode, boolean conclusionEligible, int priority,
                               String section, String relevanceHint) {
        public Presentation {
            mode = mode == null || mode.isBlank() ? "WHEN_RELEVANT" : mode.trim().toUpperCase();
            if (!List.of("ALWAYS", "WHEN_RELEVANT", "EXCEPTION_ONLY", "SUPPORTING").contains(mode)) {
                mode = "SUPPORTING";
                conclusionEligible = false;
            }
        }

        static Presentation defaultPolicy() {
            return new Presentation("WHEN_RELEVANT", true, 0, null, null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("mode", mode);
            values.put("conclusionEligible", conclusionEligible);
            values.put("priority", priority);
            if (section != null && !section.isBlank()) values.put("section", section);
            if (relevanceHint != null && !relevanceHint.isBlank()) values.put("relevanceHint", relevanceHint);
            return Map.copyOf(values);
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null && item != null) result.put(String.valueOf(key), item); });
        return result;
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        values.forEach(item -> { Map<String, Object> mapped = map(item); if (!mapped.isEmpty()) result.add(mapped); });
        return List.copyOf(result);
    }

    public static String text(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    public static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
    public static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        return fallback;
    }
    public static int integer(Object value, int fallback) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }
}
