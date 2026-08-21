package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
class PythonTemplateArgumentResolver {
    private final ObjectMapper objectMapper;

    Map<String, Object> resolve(String schemaJson, Map<String, Object> supplied) {
        Map<String, Object> schema = schema(schemaJson);
        Map<String, Object> properties = map(schema.get("properties"));
        Map<String, Object> resolved = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            Map<String, Object> definition = map(value);
            Object defaultValue = definition.get("default");
            if (defaultValue == null) defaultValue = definition.get("defaultValue");
            if (defaultValue == null) defaultValue = definition.get("default_value");
            if (defaultValue != null) resolved.put(name, convert(name, defaultValue, definition));
        });
        boolean additional = !(schema.get("additionalProperties") instanceof Boolean allowed) || allowed;
        if (supplied != null) supplied.forEach((name, value) -> {
            if (name != null && value != null && (properties.containsKey(name) || additional)) {
                resolved.put(name, properties.containsKey(name) ? convert(name, value, map(properties.get(name))) : value);
            }
        });
        return Map.copyOf(resolved);
    }

    private Object convert(String name, Object value, Map<String, Object> definition) {
        String type = String.valueOf(definition.getOrDefault("type", "")).trim().toLowerCase();
        Object converted = switch (type) {
            case "string", "file" -> String.valueOf(value);
            case "integer" -> integer(name, value);
            case "number" -> number(name, value);
            case "boolean" -> bool(name, value);
            case "array" -> structured(name, value, List.class, "数组");
            case "object" -> structured(name, value, Map.class, "对象");
            default -> value;
        };
        if (definition.get("enum") instanceof List<?> choices
                && choices.stream().noneMatch(choice -> Objects.equals(choice, converted))) {
            throw new IllegalArgumentException("Python template parameter " + name + " is not an allowed enum value");
        }
        return converted;
    }

    private Object integer(String name, Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return value;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ex) {
            throw invalid(name, "整数", value);
        }
    }

    private Object number(String name, Object value) {
        if (value instanceof Number) return value;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ex) {
            throw invalid(name, "数字", value);
        }
    }

    private Object bool(String name, Object value) {
        if (value instanceof Boolean) return value;
        String text = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text)) return true;
        if ("false".equals(text) || "0".equals(text)) return false;
        throw invalid(name, "布尔值", value);
    }

    private Object structured(String name, Object value, Class<?> expected, String label) {
        if (expected.isInstance(value)) return value;
        if (value instanceof String text) {
            try {
                Object parsed = objectMapper.readValue(text, Object.class);
                if (expected.isInstance(parsed)) return parsed;
            } catch (Exception ignored) {
            }
        }
        throw invalid(name, label, value);
    }

    private IllegalArgumentException invalid(String name, String expected, Object value) {
        return new IllegalArgumentException("Python template parameter " + name + " must be " + expected + ", actual: " + value);
    }

    Map<String, Object> schema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return Map.of("type", "object", "properties", Map.of());
        try {
            return objectMapper.readValue(schemaJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Python template input schema", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? objectMapper.convertValue(value, new TypeReference<>() {
        }) : Map.of();
    }

}
