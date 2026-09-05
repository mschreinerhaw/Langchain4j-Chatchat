package com.chatchat.agents.orchestration.analysis.graph;

import java.util.*;

/** Nested collection positions are distinct from top-level dataset record positions. */
final class NestedRecordReader {
    static List<Map<String, Object>> catalog(List<Map<String, Object>> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < records.size() && result.size() < 64; i++) walk(records.get(i), i + 1, List.of(), result);
        return List.copyOf(result);
    }
    private static void walk(Object value, int record, List<Object> path, List<Map<String, Object>> result) {
        if (path.size() > 8 || result.size() >= 64) return;
        if (value instanceof List<?> list) {
            result.add(Map.of("record", record, "path", path, "itemCount", list.size(), "indexBase", 0));
            for (int i = 0; i < Math.min(20, list.size()); i++) {
                var next = new ArrayList<>(path); next.add(i); walk(list.get(i), record, next, result);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) if (entry.getKey() instanceof String key) {
                var next = new ArrayList<>(path); next.add(key); walk(entry.getValue(), record, next, result);
            }
        }
    }
    static Map<String, Object> read(List<Map<String, Object>> records, Map<String, Object> request) {
        int record = integer(request.get("record"));
        if (record < 1 || record > records.size() || !(request.get("path") instanceof List<?> path)
            || path.isEmpty() || path.size() > 8) throw new IllegalArgumentException("Invalid nested record locator");
        Object value = records.get(record - 1);
        for (Object part : path) {
            if (part instanceof String key && value instanceof Map<?, ?> map && map.containsKey(key)) value = map.get(key);
            else if (part instanceof Number && value instanceof List<?> list) {
                int index = integer(part);
                if (index < 0 || index >= list.size()) throw new IllegalArgumentException("Nested path index is outside collection");
                value = list.get(index);
            } else throw new IllegalArgumentException("Nested collection path is unavailable");
        }
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Nested locator must identify a collection");
        int from = integer(request.getOrDefault("fromItem", 0));
        int limit = integer(request.getOrDefault("limit", 20));
        if (from < 0 || from > list.size() || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid nested page; availableItemCount=" + list.size());
        int to = Math.min(list.size(), from + limit);
        return Map.of("record", record, "path", path, "availableItemCount", list.size(),
            "fromItem", from, "toItemExclusive", to, "items", list.subList(from, to));
    }
    private static int integer(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue())
            throw new IllegalArgumentException("Expected integer record position");
        return number.intValue();
    }
}
