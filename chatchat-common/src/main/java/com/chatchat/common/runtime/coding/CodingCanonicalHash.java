package com.chatchat.common.runtime.coding;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** coding-canonical-json-v1: restricted, exact JSON, not general RFC 8785 or arbitrary JSON hashing. */
public final class CodingCanonicalHash {
    public static final String ALGORITHM = "coding-canonical-json-v1";
    private CodingCanonicalHash() { }

    public static String sha256(CodingTaskContract task) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalJson(task).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String canonicalJson(CodingTaskContract task) {
        CodingProtocol.required(task, "task");
        Map<String, Object> payload = fields(task);
        if (task.runtimeContext().isEmpty()) payload.remove("runtimeContext");
        // Scope attributes, requestId, conversationId and environment are transport context, not task semantics.
        payload.put("scope", Map.of("tenantId", task.scope().tenantId(), "userId", task.scope().userId(), "runId", task.scope().runId()));
        return encode(payload);
    }

    private static Map<String, Object> fields(Record record) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try { result.put(component.getName(), component.getAccessor().invoke(record)); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot encode coding record", failure); }
        }
        return result;
    }

    private static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Enum<?> symbol) return quote(symbol.name());
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Long || value instanceof Integer) return value.toString();
        if (value instanceof Instant time) return quote(new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(time));
        if (value instanceof Record record) return encode(fields(record));
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put((String) key, item));
            return sorted.entrySet().stream().map(entry -> quote(entry.getKey()) + ":" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> items) {
            var entries = new java.util.ArrayList<String>(); items.forEach(item -> entries.add(encode(item)));
            return String.join(",", entries).transform(text -> "[" + text + "]");
        }
        throw new IllegalArgumentException("Unsupported canonical value: " + value.getClass());
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b"); case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> { if (c < 32) result.append(String.format("\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
}
