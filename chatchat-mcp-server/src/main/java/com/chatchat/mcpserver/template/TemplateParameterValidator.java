package com.chatchat.mcpserver.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TemplateParameterValidator {

    private final ObjectMapper objectMapper;

    public Map<String, Object> collect(String schemaJson, Map<String, Object> explicitParameters,
                                       Map<String, Object> source) {
        Map<String, Object> schema = readSchema(schemaJson);
        Map<String, Object> properties = objectMap(schema.get("properties"));
        Map<String, Object> collected = new LinkedHashMap<>(explicitParameters == null ? Map.of() : explicitParameters);
        if (source == null || source.isEmpty() || properties.isEmpty()) {
            return collected;
        }
        for (String name : properties.keySet()) {
            if (!collected.containsKey(name) && source.containsKey(name)) {
                collected.put(name, source.get(name));
            }
        }
        return collected;
    }

    public Map<String, Object> validate(String templateId, String schemaJson, Map<String, Object> parameters) {
        Map<String, Object> schema = readSchema(schemaJson);
        Map<String, Object> input = parameters == null ? Map.of() : parameters;
        Map<String, Object> properties = objectMap(schema.get("properties"));
        List<String> required = stringList(schema.get("required"));
        Map<String, Object> normalized = new LinkedHashMap<>();

        for (String name : required) {
            if (isBlankValue(input.get(name))) {
                throw new IllegalArgumentException("Template parameter is required: " + name
                    + " for template " + templateId + ". Pass it under parameters." + name);
            }
        }

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            Map<String, Object> property = objectMap(properties.get(name));
            Object value = coerceAndValidate(templateId, name, entry.getValue(), property);
            normalized.put(name, value);
        }
        return normalized;
    }

    public Map<String, Object> validateDeclaredOnly(String templateId,
                                                    String schemaJson,
                                                    Map<String, Object> explicitParameters,
                                                    Map<String, Object> source) {
        Map<String, Object> schema = readSchema(schemaJson);
        Map<String, Object> properties = objectMap(schema.get("properties"));
        List<String> required = stringList(schema.get("required"));
        Map<String, Object> collected = new LinkedHashMap<>();

        for (String name : properties.keySet()) {
            Object value = null;
            if (explicitParameters != null && explicitParameters.containsKey(name)) {
                value = explicitParameters.get(name);
            } else if (source != null && source.containsKey(name)) {
                value = source.get(name);
            }
            if (value != null) {
                collected.put(name, value);
            }
        }

        for (String name : required) {
            if (isBlankValue(collected.get(name))) {
                throw new IllegalArgumentException("Template parameter is required: " + name
                    + " for template " + templateId + ". Pass it under parameters." + name);
            }
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : collected.entrySet()) {
            Map<String, Object> property = objectMap(properties.get(entry.getKey()));
            normalized.put(entry.getKey(), coerceAndValidate(templateId, entry.getKey(), entry.getValue(), property));
        }
        return normalized;
    }

    private Object coerceAndValidate(String templateId, String name, Object rawValue, Map<String, Object> property) {
        if (isBlankValue(rawValue)) {
            return rawValue;
        }
        String type = text(property.get("type"));
        Object value = rawValue;
        if ("integer".equals(type)) {
            value = toInteger(templateId, name, rawValue);
        } else if ("number".equals(type)) {
            value = toNumber(templateId, name, rawValue);
        } else if ("boolean".equals(type)) {
            value = toBoolean(templateId, name, rawValue);
        } else if ("string".equals(type) || type == null) {
            value = String.valueOf(rawValue).trim();
        }
        validateEnum(templateId, name, value, property);
        validateString(templateId, name, value, property);
        validateNumber(templateId, name, value, property);
        return value;
    }

    private void validateEnum(String templateId, String name, Object value, Map<String, Object> property) {
        List<String> allowed = stringList(property.get("enum"));
        if (allowed.isEmpty() || value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (allowed.stream().noneMatch(item -> item.equals(text))) {
            throw new IllegalArgumentException("Template parameter " + name + " for template " + templateId
                + " must be one of " + allowed);
        }
    }

    private void validateString(String templateId, String name, Object value, Map<String, Object> property) {
        if (!(value instanceof String text)) {
            return;
        }
        Integer minLength = integer(property.get("minLength"));
        Integer maxLength = integer(property.get("maxLength"));
        if (minLength != null && text.length() < minLength) {
            throw new IllegalArgumentException("Template parameter " + name + " for template " + templateId
                + " length must be at least " + minLength);
        }
        if (maxLength != null && text.length() > maxLength) {
            throw new IllegalArgumentException("Template parameter " + name + " for template " + templateId
                + " length must be at most " + maxLength);
        }
        String pattern = text(property.get("pattern"));
        if (pattern != null && !Pattern.compile(pattern).matcher(text).matches()) {
            throw new IllegalArgumentException("Template parameter " + name + " for template " + templateId
                + " does not match required pattern");
        }
    }

    private void validateNumber(String templateId, String name, Object value, Map<String, Object> property) {
        if (!(value instanceof Number number)) {
            return;
        }
        Double minimum = decimal(property.get("minimum"));
        Double maximum = decimal(property.get("maximum"));
        double actual = number.doubleValue();
        if (minimum != null && actual < minimum) {
            throw new IllegalArgumentException("Template parameter " + name + " for template " + templateId
                + " must be >= " + formatNumber(minimum));
        }
        if (maximum != null && actual > maximum) {
            throw new IllegalArgumentException("Template parameter " + name + " for template " + templateId
                + " must be <= " + formatNumber(maximum));
        }
    }

    private Map<String, Object> readSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(schemaJson, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Template parameterSchema is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(item -> item != null && !String.valueOf(item).isBlank())
            .map(item -> String.valueOf(item).trim())
            .toList();
    }

    private Integer toInteger(String templateId, String name, Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ex) {
            throw typeError(templateId, name, "integer");
        }
    }

    private Double toNumber(String templateId, String name, Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ex) {
            throw typeError(templateId, name, "number");
        }
    }

    private Boolean toBoolean(String templateId, String name, Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.valueOf(text);
        }
        throw typeError(templateId, name, "boolean");
    }

    private IllegalArgumentException typeError(String templateId, String name, String type) {
        return new IllegalArgumentException("Template parameter " + name + " for template " + templateId
            + " must be " + type);
    }

    private boolean isBlankValue(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String formatNumber(Double value) {
        return value == null ? "" : value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }
}
