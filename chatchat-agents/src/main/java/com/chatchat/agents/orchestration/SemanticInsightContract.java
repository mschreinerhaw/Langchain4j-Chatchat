package com.chatchat.agents.orchestration;

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

    public record Recipe(String id, String operator, String label, Map<String, Object> parameters) {
        static Recipe from(Map<String, Object> value) {
            Map<String, Object> parameters = new LinkedHashMap<>(value);
            parameters.keySet().removeAll(List.of("id", "operator", "label"));
            return new Recipe(text(value.get("id")), text(value.get("operator")),
                text(value.get("label")), Collections.unmodifiableMap(parameters));
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

    static String text(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
