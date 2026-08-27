package com.chatchat.agents.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    public CompiledToolArguments compileCanonical(Map<String, Object> semanticArguments,
                                                  Map<String, Object> schema) {
        CompilationResult result = compile(semanticArguments, schema);
        return new CompiledToolArguments(
            null,
            schemaFingerprint(schema),
            result.status(),
            result.parameters(),
            result.validationErrors(),
            result.repairs()
        );
    }

    public CompilationResult compile(Map<String, Object> semanticArguments, Map<String, Object> schema) {
        Map<String, Object> source = semanticArguments == null
            ? Map.of() : new LinkedHashMap<>(semanticArguments);
        if (schema == null || schema.isEmpty() || !(schema.get("properties") instanceof Map<?, ?> rawProperties)) {
            return new CompilationResult("READY", source, List.of(), List.of());
        }
        Map<String, Object> compiled = new LinkedHashMap<>();
        List<ValidationError> errors = new ArrayList<>();
        List<Repair> repairs = new ArrayList<>();
        promoteArgumentEnvelopes(source, rawProperties, repairs);
        Set<String> consumed = new LinkedHashSet<>();
        Set<String> requiredFields = new LinkedHashSet<>(stringList(schema.get("required")));
        for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Map<String, Object> property = objectMap(entry.getValue());
            SourceValue selected = sourceValue(source, name, property);
            if (selected.conflicting()) {
                errors.add(new ValidationError(name, "CONFLICTING_PARAMETER_ALIASES",
                    "Conflicting values were provided for parameter aliases " + selected.matchedNames(),
                    selected.value(), expectedType(property)));
                consumed.addAll(selected.matchedNames());
                continue;
            }
            Object value = selected.value();
            Object declaredDefault = firstPresent(property, "default", "defaultValue", "default_value");
            if (value == null && declaredDefault != null) {
                value = declaredDefault;
                repairs.add(new Repair(name, "DEFAULT_VALUE_APPLIED", null, value));
            }
            if (value == null) {
                continue;
            }
            consumed.addAll(selected.matchedNames());
            Conversion conversion = convert(name, value, property);
            if (conversion.error() != null) {
                Conversion defaultConversion = declaredDefault == null
                    ? null : convert(name, declaredDefault, property);
                if (defaultConversion != null && defaultConversion.error() == null) {
                    compiled.put(name, defaultConversion.value());
                    repairs.add(new Repair(
                        name,
                        "INVALID_OVERRIDE_DROPPED_DEFAULT_APPLIED",
                        value,
                        defaultConversion.value()
                    ));
                } else if (requiredFields.contains(name)) {
                    errors.add(conversion.error());
                } else {
                    repairs.add(new Repair(
                        name,
                        "INVALID_OPTIONAL_OVERRIDE_DROPPED",
                        value,
                        null
                    ));
                }
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
        for (String required : requiredFields) {
            if (!hasValue(compiled.get(required))) {
                errors.add(new ValidationError(required, "REQUIRED_PARAMETER_MISSING",
                    "Missing required parameter " + required, source.get(required), expectedType(objectMap(rawProperties.get(required)))));
            }
        }
        return new CompilationResult(errors.isEmpty() ? "READY" : "INVALID_TOOL_ARGUMENTS",
            compiled, List.copyOf(errors), List.copyOf(repairs));
    }

    private void promoteArgumentEnvelopes(Map<String, Object> source,
                                          Map<?, ?> rawProperties,
                                          List<Repair> repairs) {
        Map<String, String> acceptedFields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> propertyEntry : rawProperties.entrySet()) {
            if (propertyEntry.getKey() == null) {
                continue;
            }
            String propertyName = String.valueOf(propertyEntry.getKey());
            acceptedFields.putIfAbsent(canonical(propertyName), propertyName);
            Map<String, Object> property = objectMap(propertyEntry.getValue());
            stringList(property.get("aliases"))
                .forEach(alias -> acceptedFields.putIfAbsent(canonical(alias), propertyName));
            stringList(property.get("acceptedSources"))
                .forEach(alias -> acceptedFields.putIfAbsent(canonical(alias), propertyName));
        }
        Set<String> publishedFields = rawProperties.keySet().stream()
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .map(this::canonical)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Map.Entry<String, Object> candidate : new ArrayList<>(source.entrySet())) {
            String wrapper = candidate.getKey();
            if (publishedFields.contains(canonical(wrapper))
                || !(candidate.getValue() instanceof Map<?, ?> nested)) {
                continue;
            }
            Set<String> occupied = source.keySet().stream()
                .filter(key -> !key.equals(wrapper))
                .map(this::canonical)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean contractFieldFound = false;
            for (Map.Entry<?, ?> nestedEntry : nested.entrySet()) {
                if (nestedEntry.getKey() == null || !hasValue(nestedEntry.getValue())) {
                    continue;
                }
                String nestedName = String.valueOf(nestedEntry.getKey());
                String targetName = acceptedFields.get(canonical(nestedName));
                if (targetName == null) {
                    continue;
                }
                contractFieldFound = true;
                if (occupied.add(canonical(targetName))) {
                    source.put(targetName, nestedEntry.getValue());
                    repairs.add(new Repair(
                        targetName,
                        "NESTED_SOURCE_PROMOTED",
                        wrapper + "." + nestedName,
                        nestedEntry.getValue()
                    ));
                }
            }
            if (contractFieldFound) {
                source.remove(wrapper);
            }
        }
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
                case "array" -> arrayValue(field, value, property);
                case "object" -> objectValue(field, value, property);
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

    private Object arrayValue(String field, Object value, Map<String, Object> property) {
        List<?> source = value instanceof List<?> list ? list : List.of(value);
        Map<String, Object> itemSchema = objectMap(property.get("items"));
        if (itemSchema.isEmpty()) {
            return List.copyOf(source);
        }
        List<Object> converted = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Conversion item = convert(field + "[" + index + "]", source.get(index), itemSchema);
            if (item.error() != null) {
                throw new IllegalArgumentException(item.error().message());
            }
            converted.add(item.value());
        }
        return List.copyOf(converted);
    }

    private Object objectValue(String field, Object value, Map<String, Object> property) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> source = objectMap(raw);
        if (!(property.get("properties") instanceof Map<?, ?> rawProperties)) {
            return Map.copyOf(source);
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        Set<String> consumed = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>(stringList(property.get("required")));
        for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Map<String, Object> childSchema = objectMap(entry.getValue());
            SourceValue selected = sourceValue(source, name, childSchema);
            if (selected.conflicting()) {
                throw new IllegalArgumentException("Conflicting aliases for " + field + "." + name);
            }
            Object childValue = selected.value();
            if (childValue == null) {
                childValue = firstPresent(childSchema, "default", "defaultValue", "default_value");
            }
            if (childValue == null) {
                if (required.contains(name)) {
                    throw new IllegalArgumentException("Missing required parameter " + field + "." + name);
                }
                continue;
            }
            Conversion child = convert(field + "." + name, childValue, childSchema);
            if (child.error() != null) {
                throw new IllegalArgumentException(child.error().message());
            }
            converted.put(name, child.value());
            consumed.addAll(selected.matchedNames());
        }
        if (Boolean.TRUE.equals(property.get("additionalProperties"))) {
            source.forEach((key, item) -> {
                if (!consumed.contains(key)) converted.putIfAbsent(key, item);
            });
        }
        return Map.copyOf(converted);
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
        // Keep the legacy convenience for value-like names (table -> tableName), but never
        // apply it to routing identities where an object such as "asset" must not become text.
        if (field.toLowerCase(Locale.ROOT).endsWith("name")
            && !Set.of("assetname", "toolname", "servicename").contains(canonical(field))) {
            candidates.add(field.substring(0, field.length() - 4));
        }
        List<Map.Entry<String, Object>> matches = source.entrySet().stream()
            .filter(entry -> hasValue(entry.getValue()))
            .filter(entry -> candidates.stream().anyMatch(candidate ->
                canonical(candidate).equals(canonical(entry.getKey()))))
            .toList();
        if (matches.isEmpty()) {
            return new SourceValue(field, null, false, List.of());
        }
        Map.Entry<String, Object> selected = matches.stream()
            .filter(entry -> field.equals(entry.getKey()))
            .findFirst()
            .orElse(matches.get(0));
        boolean conflicting = matches.stream()
            .map(Map.Entry::getValue)
            .anyMatch(candidate -> !valuesEqual(selected.getValue(), candidate));
        return new SourceValue(selected.getKey(), selected.getValue(), conflicting,
            matches.stream().map(Map.Entry::getKey).toList());
    }

    private String canonical(String value) {
        return value == null ? "" : value.replace("_", "").replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
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

    private String schemaFingerprint(Map<String, Object> schema) {
        try {
            ObjectMapper canonicalMapper = OBJECT_MAPPER.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            byte[] canonicalSchema = canonicalMapper.writeValueAsString(
                schema == null ? Map.of() : schema).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalSchema);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint tool input schema", ex);
        }
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

    private record SourceValue(String sourceName, Object value, boolean conflicting, List<String> matchedNames) {
    }

    private record Conversion(Object value, ValidationError error) {
    }
}
