package com.chatchat.agents.orchestration.analysis.graph;

import java.math.BigDecimal;
import java.util.*;

/** Explicit JSON Schema subset for typed batch results. Unsupported contracts fail closed. */
final class WorkspaceResultSchema {
    private static final Set<String> KEYS = Set.of("type", "properties", "required", "items", "enum", "additionalProperties", "description", "title");
    static void checkContract(Map<String,Object> schema) {
        if (!KEYS.containsAll(schema.keySet())) throw new IllegalArgumentException("Unsupported outputSchema keyword");
        if (!(schema.get("type") instanceof String type) || !Set.of("object","array","string","number","integer","boolean","null").contains(type))
            throw new IllegalArgumentException("outputSchema requires a supported type");
        if (schema.containsKey("enum") && !(schema.get("enum") instanceof List<?>)) throw new IllegalArgumentException("enum must be an array");
        if ("object".equals(type)) {
            Map<String,Object> properties = object(schema.getOrDefault("properties", Map.of()));
            properties.values().forEach(value -> checkContract(object(value)));
            if (schema.containsKey("required") && (!(schema.get("required") instanceof List<?> required) || !properties.keySet().containsAll(required)))
                throw new IllegalArgumentException("required must name declared properties");
            if (schema.containsKey("additionalProperties") && !(schema.get("additionalProperties") instanceof Boolean))
                throw new IllegalArgumentException("additionalProperties must be boolean");
        }
        if ("array".equals(type)) checkContract(object(schema.get("items")));
    }
    static void validate(Object value, Map<String,Object> schema) {
        String type = String.valueOf(schema.get("type"));
        boolean valid = switch(type) {
            case "object" -> value instanceof Map<?,?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Output does not match schema type " + type);
        if (schema.get("enum") instanceof List<?> options && !options.contains(value)) throw new IllegalArgumentException("Output is outside enum");
        if (value instanceof Map<?,?> values && "object".equals(type)) {
            var properties = object(schema.getOrDefault("properties", Map.of()));
            var required = (List<?>)schema.getOrDefault("required", List.of());
            if (!values.keySet().containsAll(required)) throw new IllegalArgumentException("Output lacks required fields");
            if (Boolean.FALSE.equals(schema.get("additionalProperties")) && !properties.keySet().containsAll(values.keySet()))
                throw new IllegalArgumentException("Output has undeclared fields");
            values.forEach((key,item) -> { if (properties.containsKey(key)) validate(item, object(properties.get(key))); });
        }
        if (value instanceof List<?> values && "array".equals(type)) values.forEach(item -> validate(item, object(schema.get("items"))));
    }
    static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> raw)) throw new IllegalArgumentException("Expected an object");
        var result = new LinkedHashMap<String,Object>();
        raw.forEach((key,item) -> result.put(String.valueOf(key),item));
        return result;
    }
}
