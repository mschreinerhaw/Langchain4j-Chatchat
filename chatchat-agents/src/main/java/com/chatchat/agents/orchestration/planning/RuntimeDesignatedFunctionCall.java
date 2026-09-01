package com.chatchat.agents.orchestration.planning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Runtime-owned designation proving that the model is not selecting a tool. */
public record RuntimeDesignatedFunctionCall(
    String contractVersion,
    String toolName,
    String source,
    boolean required
) {
    public static final String CONTRACT_VERSION = "runtime_designated_function_call.v1";
    public static final String CONTEXT_KEY = "__runtimeDesignatedFunctionCall";

    public RuntimeDesignatedFunctionCall(String toolName, String source) {
        this(CONTRACT_VERSION, required(toolName), required(source), true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", contractVersion);
        value.put("toolName", toolName);
        value.put("source", source);
        value.put("required", required);
        return Map.copyOf(value);
    }

    public static Optional<RuntimeDesignatedFunctionCall> from(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Optional.empty();
        String version = text(map.get("contractVersion"));
        String toolName = text(map.get("toolName"));
        String source = text(map.get("source"));
        boolean required = Boolean.TRUE.equals(map.get("required"));
        if (!CONTRACT_VERSION.equals(version) || toolName == null || source == null || !required) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeDesignatedFunctionCall(version, toolName, source, true));
    }

    private static String required(String value) {
        String normalized = text(value);
        if (normalized == null) throw new IllegalArgumentException("Runtime function-call designation value is required");
        return normalized;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
