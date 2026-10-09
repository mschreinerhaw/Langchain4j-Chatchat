package com.chatchat.agents.orchestration.analysis.report;

import java.util.*;

/** Validates the small, closed declarative protocol using its shared schema, without external schema resolution. */
final class ReportBlockSchema {
    static void validate(Map<String, Object> schema, Object value) {
        String type = String.valueOf(schema.getOrDefault("type", ""));
        require(switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "" -> true;
            default -> false;
        });
        if (schema.get("enum") instanceof List<?> allowed) require(allowed.contains(value));
        if (value instanceof String text) {
            require(text.length() >= number(schema, "minLength", 0) && text.length() <= number(schema, "maxLength", Integer.MAX_VALUE));
            if (schema.get("pattern") instanceof String pattern) require(text.matches(pattern));
        }
        if (value instanceof List<?> values) {
            require(values.size() >= number(schema, "minItems", 0) && values.size() <= number(schema, "maxItems", Integer.MAX_VALUE));
            if (Boolean.TRUE.equals(schema.get("uniqueItems"))) require(new HashSet<>(values).size() == values.size());
            Map<String, Object> items = map(schema.get("items"));
            values.forEach(item -> validate(items, item));
        }
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> properties = map(schema.get("properties"));
            if (schema.get("required") instanceof List<?> required) require(values.keySet().containsAll(required));
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) require(properties.keySet().containsAll(values.keySet()));
            values.forEach((key, item) -> validate(map(properties.get(key)), item));
        }
    }
    private static int number(Map<String, Object> schema, String key, int fallback) {
        return schema.get(key) instanceof Number number ? number.intValue() : fallback;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
    static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("INVALID_REPORT_BLOCK_SCHEMA");
    }
}
