package com.chatchat.common.runtime.coding;

import java.util.*;

/** Public, secret-free configuration snapshot. It grants no permissions beyond Authorization. */
public final class CodingRuntimeContext {
    private CodingRuntimeContext() { }
    public static Map<String, Object> validate(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        CodingProtocol.require(value.keySet().stream().allMatch(Set.of("schemaVersion", "capturedAt",
            "consistency", "model", "skills", "configuredSkillIds")::contains), "Unknown Coding context field");
        CodingProtocol.require("runtime_os.coding.context.v1".equals(value.get("schemaVersion")), "Unknown Coding context version");
        CodingProtocol.require("TASK_SNAPSHOT".equals(value.get("consistency")), "Coding context must be task scoped");
        return freeze(value, 0);
    }
    private static Map<String, Object> freeze(Map<?, ?> value, int depth) {
        CodingProtocol.require(depth < 12 && value.size() <= 128, "Coding context is too large");
        Map<String, Object> result = new TreeMap<>();
        value.forEach((key, item) -> {
            CodingProtocol.require(key instanceof String, "Coding context keys must be strings");
            String name = (String) key;
            CodingProtocol.require(!name.toLowerCase(Locale.ROOT).matches(".*(password|secret|api.?key|authorization|credential|baseurl|proxy).*"),
                "Sensitive configuration is forbidden in Coding context");
            result.put(name, freezeValue(item, depth + 1));
        });
        return Collections.unmodifiableMap(result);
    }
    private static Object freezeValue(Object value, int depth) {
        CodingProtocol.require(depth < 12, "Coding context nesting limit");
        if (value instanceof Map<?, ?> map) return freeze(map, depth);
        if (value instanceof List<?> list) {
            CodingProtocol.require(list.size() <= 128, "Coding context list limit");
            return list.stream().map(item -> freezeValue(item, depth + 1)).toList();
        }
        if (value instanceof String text) {
            CodingProtocol.require(text.length() <= 16000, "Coding context text limit");
            return text;
        }
        CodingProtocol.require(value == null || value instanceof Boolean || value instanceof Number,
            "Unsupported Coding context value");
        return value;
    }
}
