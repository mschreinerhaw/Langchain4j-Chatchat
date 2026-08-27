package com.chatchat.agents.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Schema-compiled, immutable arguments accepted by the canonical Runtime protocol. */
public record CompiledToolArguments(
    String schemaVersion,
    String schemaFingerprint,
    String status,
    Map<String, Object> values,
    List<ToolArgumentCompiler.ValidationError> validationErrors,
    List<ToolArgumentCompiler.Repair> repairs
) {
    public static final String SCHEMA_VERSION = "compiled_tool_arguments.v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public CompiledToolArguments {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported compiled argument schema: " + schemaVersion);
        }
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint.trim();
        status = status == null || status.isBlank() ? "INVALID_TOOL_ARGUMENTS" : status.trim();
        values = immutableMap(values);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        repairs = repairs == null ? List.of() : List.copyOf(repairs);
    }

    public boolean valid() {
        return validationErrors.isEmpty();
    }

    public String structuredError(String toolName, String action) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                "status", status,
                "toolName", toolName == null ? "" : toolName,
                "action", action == null ? "" : action,
                "validationErrors", validationErrors
            ));
        } catch (Exception ignored) {
            return status + ": " + validationErrors;
        }
    }

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), immutableValue(item)));
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            list.forEach(item -> nested.add(immutableValue(item)));
            return Collections.unmodifiableList(nested);
        }
        if (value instanceof Set<?> set) {
            Set<Object> nested = new LinkedHashSet<>();
            set.forEach(item -> nested.add(immutableValue(item)));
            return Collections.unmodifiableSet(nested);
        }
        return value;
    }
}
