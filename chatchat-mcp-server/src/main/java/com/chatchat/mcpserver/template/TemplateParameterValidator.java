package com.chatchat.mcpserver.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class TemplateParameterValidator {

    private final ObjectMapper objectMapper;

    public TemplateParameterValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> collect(String schemaJson, Map<String, Object> parameters, Map<String, Object> source) {
        Map<String, Object> collected = new LinkedHashMap<>();
        if (parameters != null) {
            collected.putAll(parameters);
        }
        Map<String, Object> schema = schema(schemaJson);
        Map<String, Object> properties = map(schema.get("properties"));
        Map<String, Object> sourceMap = source == null ? Map.of() : source;
        for (String key : properties.keySet()) {
            if (!collected.containsKey(key) && sourceMap.containsKey(key)) {
                collected.put(key, sourceMap.get(key));
            }
        }
        return collected;
    }

    public Map<String, Object> validate(String templateCode, String schemaJson, Map<String, Object> parameters) {
        Map<String, Object> schema = schema(schemaJson);
        Map<String, Object> properties = map(schema.get("properties"));
        List<String> required = stringList(schema.get("required"));
        Map<String, Object> validated = new LinkedHashMap<>();
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        for (String key : required) {
            Object value = values.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Template parameter is required: " + key + " (parameters." + key + ")");
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Map<String, Object> property = map(properties.get(key));
            if (!property.isEmpty()) {
                validateType(key, value, property);
                validateNumber(key, value, property);
                validateString(key, value, property);
            }
            validated.put(key, value);
        }
        return validated;
    }

    private void validateType(String key, Object value, Map<String, Object> property) {
        String type = text(property.get("type"));
        if (type == null || value == null) {
            return;
        }
        boolean ok = switch (type) {
            case "integer" -> value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || String.valueOf(value).matches("-?\\d+");
            case "number" -> value instanceof Number || String.valueOf(value).matches("-?\\d+(\\.\\d+)?");
            case "boolean" -> value instanceof Boolean || "true".equalsIgnoreCase(String.valueOf(value))
                || "false".equalsIgnoreCase(String.valueOf(value));
            case "string" -> value instanceof CharSequence;
            default -> true;
        };
        if (!ok) {
            throw new IllegalArgumentException("Template parameter type mismatch: " + key + " (parameters." + key + ")");
        }
    }

    private void validateNumber(String key, Object value, Map<String, Object> property) {
        if (value == null || !(property.containsKey("minimum") || property.containsKey("maximum"))) {
            return;
        }
        double number;
        try {
            number = Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return;
        }
        Double minimum = number(property.get("minimum"));
        Double maximum = number(property.get("maximum"));
        if (minimum != null && number < minimum) {
            throw new IllegalArgumentException("Template parameter is below minimum: " + key + " (parameters." + key + ")");
        }
        if (maximum != null && number > maximum) {
            throw new IllegalArgumentException("Template parameter exceeds maximum: " + key + " (parameters." + key + ")");
        }
    }

    private void validateString(String key, Object value, Map<String, Object> property) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        Integer minLength = integer(property.get("minLength"));
        Integer maxLength = integer(property.get("maxLength"));
        if (minLength != null && text.length() < minLength) {
            throw new IllegalArgumentException("Template parameter is shorter than minLength: " + key + " (parameters." + key + ")");
        }
        if (maxLength != null && text.length() > maxLength) {
            throw new IllegalArgumentException("Template parameter exceeds maxLength: " + key + " (parameters." + key + ")");
        }
        String pattern = text(property.get("pattern"));
        if (pattern != null && !Pattern.compile(pattern).matcher(text).matches()) {
            throw new IllegalArgumentException("Template parameter does not match pattern: " + key + " (parameters." + key + ")");
        }
    }

    private Map<String, Object> schema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(schemaJson, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> item == null ? null : String.valueOf(item).trim())
                .filter(item -> item != null && !item.isBlank())
                .toList();
        }
        return List.of();
    }

    private Double number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer integer(Object value) {
        Double number = number(value);
        return number == null ? null : number.intValue();
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
