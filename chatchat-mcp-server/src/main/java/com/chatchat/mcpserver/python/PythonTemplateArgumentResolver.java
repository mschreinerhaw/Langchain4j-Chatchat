package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            if (definition.get("default") != null) resolved.put(name, definition.get("default"));
            else if (definition.get("defaultValue") != null) resolved.put(name, definition.get("defaultValue"));
            else if (definition.get("default_value") != null) resolved.put(name, definition.get("default_value"));
        });
        boolean additional = !(schema.get("additionalProperties") instanceof Boolean allowed) || allowed;
        if (supplied != null) supplied.forEach((name, value) -> {
            if (name != null && value != null && (properties.containsKey(name) || additional)) resolved.put(name, value);
        });
        List<String> missing = strings(schema.get("required")).stream()
            .filter(name -> !resolved.containsKey(name) || resolved.get(name) == null
                || resolved.get(name) instanceof String text && text.isBlank())
            .toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException(
            "Python template required parameters have no value or default: " + missing);
        return Map.copyOf(resolved);
    }

    Map<String, Object> schema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return Map.of("type", "object", "properties", Map.of());
        try { return objectMapper.readValue(schemaJson, new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid Python template input schema", ex); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? objectMapper.convertValue(value, new TypeReference<>() {}) : Map.of();
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }
}
