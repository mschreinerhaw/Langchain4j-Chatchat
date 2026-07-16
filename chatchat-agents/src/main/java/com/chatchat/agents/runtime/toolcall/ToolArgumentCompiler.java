package com.chatchat.agents.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministically compiles semantic model arguments against MCP JSON Schema metadata. */
public final class ToolArgumentCompiler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public CompilationResult compile(Map<String, Object> semanticArguments, Map<String, Object> schema) {
        Map<String, Object> source = semanticArguments == null
            ? Map.of() : new LinkedHashMap<>(semanticArguments);
        if (schema == null || schema.isEmpty() || !(schema.get("properties") instanceof Map<?, ?> rawProperties)) {
            return new CompilationResult("READY", source, List.of(), List.of());
        }
        Map<String, Object> compiled = new LinkedHashMap<>();
        List<ValidationError> errors = new ArrayList<>();
        List<Repair> repairs = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Map<String, Object> property = objectMap(entry.getValue());
            SourceValue selected = sourceValue(source, name, property);
            Object value = selected.value();
            if (value == null && property.containsKey("default")) {
                value = property.get("default");
                repairs.add(new Repair(name, "DEFAULT_VALUE_APPLIED", null, value));
            }
            if (value == null) {
                continue;
            }
            consumed.add(selected.sourceName());
            Conversion conversion = convert(name, value, property);
            if (conversion.error() != null) {
                errors.add(conversion.error());
                continue;
            }
            compiled.put(name, conversion.value());
            if (!name.equals(selected.sourceName()) || !valuesEqual(value, conversion.value())) {
                repairs.add(new Repair(name,
                    !name.equals(selected.sourceName()) ? "ALIAS_NORMALIZED" : "TYPE_NORMALIZED",
                    value, conversion.value()));
            }
        }
        if (Boolean.TRUE.equals(schema.get("additionalProperties"))) {
            source.forEach((key, value) -> {
                if (!consumed.contains(key)) {
                    compiled.putIfAbsent(key, value);
                }
            });
        }
        for (String required : stringList(schema.get("required"))) {
            if (!hasValue(compiled.get(required))) {
                errors.add(new ValidationError(required, "REQUIRED_PARAMETER_MISSING",
                    "Missing required parameter " + required, source.get(required), expectedType(objectMap(rawProperties.get(required)))));
            }
        }
        return new CompilationResult(errors.isEmpty() ? "READY" : "INVALID_TOOL_ARGUMENTS",
            compiled, List.copyOf(errors), List.copyOf(repairs));
    }

    private Conversion convert(String field, Object value, Map<String, Object> property) {
        String type = string(property.get("type"));
        Object converted;
        try {
            converted = switch (type) {
                case "integer" -> integer(value);
                case "number" -> number(value);
                case "boolean" -> bool(value);
                case "string" -> stringValue(value, property);
                case "array" -> value instanceof List<?> ? value : List.of(value);
                case "object" -> value instanceof Map<?, ?> ? value : null;
                default -> value;
            };
        } catch (RuntimeException ex) {
            converted = null;
        }
        if (converted == null) {
            return new Conversion(null, new ValidationError(field, "INVALID_PARAMETER_TYPE",
                "Parameter cannot be converted to " + expectedType(property), value, expectedType(property)));
        }
        List<String> enumValues = stringList(property.get("enum"));
        if (!enumValues.isEmpty()) {
            String candidate = String.valueOf(converted);
            String matched = enumValues.stream().filter(item -> item.equalsIgnoreCase(candidate)).findFirst().orElse(null);
            if (matched == null) {
                return new Conversion(null, new ValidationError(field, "INVALID_ENUM_VALUE",
                    "Parameter must be one of " + enumValues, value, "enum"));
            }
            converted = matched;
        }
        return new Conversion(converted, null);
    }

    private Object stringValue(Object value, Map<String, Object> property) {
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("date".equalsIgnoreCase(string(property.get("format")))) {
            try {
                return LocalDate.parse(text, ISO_DATE).format(ISO_DATE);
            } catch (DateTimeParseException ignored) {
                return LocalDate.parse(text, BASIC_DATE).format(ISO_DATE);
            }
        }
        return text;
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value).trim());
    }

    private BigDecimal number(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value).trim());
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (Set.of("true", "1", "yes", "y", "on").contains(text)) {
            return true;
        }
        if (Set.of("false", "0", "no", "n", "off").contains(text)) {
            return false;
        }
        return null;
    }

    private SourceValue sourceValue(Map<String, Object> source, String field, Map<String, Object> property) {
        List<String> candidates = new ArrayList<>();
        candidates.add(field);
        candidates.addAll(stringList(property.get("aliases")));
        candidates.addAll(stringList(property.get("acceptedSources")));
        if (field.toLowerCase(Locale.ROOT).endsWith("name")) {
            candidates.add(field.substring(0, field.length() - 4));
        }
        for (String candidate : candidates) {
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                if (canonical(candidate).equals(canonical(entry.getKey())) && hasValue(entry.getValue())) {
                    return new SourceValue(entry.getKey(), entry.getValue());
                }
            }
        }
        return new SourceValue(field, null);
    }

    private String canonical(String value) {
        return value == null ? "" : value.replace("_", "").replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private boolean valuesEqual(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private String expectedType(Map<String, Object> property) {
        String type = string(property.get("type"));
        String format = string(property.get("format"));
        return format.isBlank() ? (type.isBlank() ? "value" : type) : type + ":" + format;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    public record CompilationResult(String status,
                                    Map<String, Object> parameters,
                                    List<ValidationError> validationErrors,
                                    List<Repair> repairs) {
        public boolean valid() {
            return validationErrors == null || validationErrors.isEmpty();
        }

        public String structuredError(String toolName, String action) {
            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "status", status,
                    "toolName", toolName == null ? "" : toolName,
                    "action", action == null ? "" : action,
                    "validationErrors", validationErrors == null ? List.of() : validationErrors
                ));
            } catch (Exception ignored) {
                return status + ": " + validationErrors;
            }
        }
    }

    public record ValidationError(String field, String errorCode, String message,
                                  Object receivedValue, String expectedType) {
    }

    public record Repair(String field, String repairCode, Object originalValue, Object repairedValue) {
    }

    private record SourceValue(String sourceName, Object value) {
    }

    private record Conversion(Object value, ValidationError error) {
    }
}
