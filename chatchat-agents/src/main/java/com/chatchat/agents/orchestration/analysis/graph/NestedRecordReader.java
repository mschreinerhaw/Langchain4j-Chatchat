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
        return readRecord(records.get(record - 1), record, request);
    }
    static Map<String, Object> readRecord(Map<String, Object> source, int record,
                                           Map<String, Object> request) {
        if (!(request.get("path") instanceof List<?> path)
            || path.isEmpty() || path.size() > 8) throw new IllegalArgumentException("Invalid nested record locator");
        Object value = locate(source, path);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Nested locator must identify a collection; use READ_VALUE for an object or field");
        int from = integer(request.getOrDefault("fromItem", 0));
        int limit = integer(request.getOrDefault("limit", 20));
        if (from < 0 || from > list.size() || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid nested page; availableItemCount=" + list.size());
        var items = new ArrayList<Object>();
        int used = 400, to = from;
        while (to < Math.min(list.size(), from + limit)) {
            Object item = project(list.get(to), request.get("fields"));
            int size = com.chatchat.agents.protocol.ModelProtocolJson.compact(item).length();
            int share = Math.max(350, (BoundedAnalysisEvidence.REQUEST_RESULT_BUDGET - used)
                / Math.max(1, Math.min(list.size(), from + limit) - to));
            if (size > share && item instanceof Map<?, ?> map) {
                var itemPath = new ArrayList<Object>(path); itemPath.add(to);
                item = partialObject(map, itemPath, share);
                size = com.chatchat.agents.protocol.ModelProtocolJson.compact(item).length();
            }
            if (used + size > BoundedAnalysisEvidence.REQUEST_RESULT_BUDGET && !items.isEmpty()) break;
            if (used + size > BoundedAnalysisEvidence.REQUEST_RESULT_BUDGET) {
                var itemPath = new ArrayList<Object>(path); itemPath.add(to);
                item = Map.of("valueOmitted", true, "path", itemPath,
                    "readHint", "Use READ_VALUE with this path and fields, or append a field key to the path");
                size = com.chatchat.agents.protocol.ModelProtocolJson.compact(item).length();
            }
            items.add(item); used += size + 1; to++;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("record", record); result.put("path", path); result.put("availableItemCount", list.size());
        result.put("fromItem", from); result.put("toItemExclusive", to); result.put("items", items);
        result.put("hasMore", to < list.size()); result.put("nextItem", to);
        result.put("truncated", to < Math.min(list.size(), from + limit));
        var available = new LinkedHashSet<String>();
        for (int i = from; i < to; i++) if (list.get(i) instanceof Map<?, ?> map)
            map.keySet().forEach(key -> available.add(String.valueOf(key)));
        if (request.get("fields") instanceof List<?> fields && !fields.isEmpty()) {
            var absent = fields.stream().filter(field -> !available.contains(String.valueOf(field))).toList();
            result.put("fieldAvailability", Map.of("requestedFields", fields,
                "availableFields", available.stream().limit(64).toList(), "absentInReturnedItems", absent));
            if (!absent.isEmpty()) result.put("fieldAvailabilityHint",
                "These requested keys are absent in the original objects on this page, not truncated. Repeating the same read will not create them; detailed data requires another source if available.");
        }
        return result;
    }

    /** Preserve exact small fields without assigning business importance to any field. */
    private static Map<String, Object> partialObject(Map<?, ?> value, List<?> path, int budget) {
        var preview = new LinkedHashMap<String, Object>();
        var result = new LinkedHashMap<String, Object>();
        result.put("path", path); result.put("partial", true); result.put("value", preview);
        result.put("readHint", "Use READ_VALUE at this path plus a field key for omitted original values");
        var entries = new ArrayList<>(value.entrySet());
        entries.sort(Comparator.comparingInt(entry -> com.chatchat.agents.protocol.ModelProtocolJson.compact(entry.getValue()).length()));
        for (var entry : entries) {
            String key = String.valueOf(entry.getKey());
            preview.put(key, entry.getValue());
            if (com.chatchat.agents.protocol.ModelProtocolJson.compact(result).length() > budget - 100) preview.remove(key);
        }
        result.put("omittedFields", value.keySet().stream().map(String::valueOf)
            .filter(key -> !preview.containsKey(key)).limit(16).toList());
        if (com.chatchat.agents.protocol.ModelProtocolJson.compact(result).length() > budget) result.remove("omittedFields");
        return result;
    }

    static Map<String, Object> readValue(Map<String, Object> source, int record, Map<String, Object> request) {
        if (!(request.get("path") instanceof List<?> path) || path.size() > 16)
            throw new IllegalArgumentException("READ_VALUE requires a path array of at most 16 elements");
        Object value = project(locate(source, path), request.get("fields"));
        var result = new LinkedHashMap<String, Object>();
        result.put("record", record); result.put("path", path);
        if (value instanceof String text) {
            int from = integer(request.getOrDefault("fromChar", 0));
            int max = integer(request.getOrDefault("maxChars", 3000));
            if (from < 0 || from > text.length() || max < 1 || max > 3000)
                throw new IllegalArgumentException("Invalid text window");
            int end = Math.min(text.length(), from + max);
            result.put("value", text.substring(from, end)); result.put("fromChar", from);
            result.put("totalChars", text.length()); result.put("nextChar", end);
            result.put("hasMore", end < text.length());
            result.put("complete", from == 0 && end == text.length());
        } else if (com.chatchat.agents.protocol.ModelProtocolJson.compact(value).length() <= 3000) {
            result.put("value", value); result.put("complete", true);
        } else {
            result.put("valueOmitted", true); result.put("complete", false);
            if (value instanceof Map<?, ?> map) {
                result.put("availableKeys", map.keySet().stream().limit(64).toList());
                result.put("keyCount", map.size());
            }
            if (value instanceof List<?> list) result.put("availableItemCount", list.size());
            result.put("readHint", "Append a key/index to path, select fields, or use READ_NESTED_RECORDS for a collection");
        }
        return result;
    }

    private static Object project(Object value, Object fields) {
        if (!(fields instanceof List<?> names) || names.isEmpty() || !(value instanceof Map<?, ?> map)) return value;
        if (names.size() > 64) throw new IllegalArgumentException("At most 64 projected field names are allowed");
        var selected = new LinkedHashMap<String, Object>();
        for (Object name : names) {
            if (!(name instanceof String key)) throw new IllegalArgumentException("fields must contain key names");
            if (map.containsKey(key)) selected.put(key, map.get(key));
        }
        return selected;
    }

    private static Object locate(Object source, List<?> path) {
        Object value = source;
        for (Object part : path) {
            if (part instanceof String key && value instanceof Map<?, ?> map && map.containsKey(key)) value = map.get(key);
            else if (part instanceof Number && value instanceof List<?> list) {
                int index = integer(part);
                if (index < 0 || index >= list.size()) throw new IllegalArgumentException("Nested path index is outside collection");
                value = list.get(index);
            } else throw new IllegalArgumentException("Nested collection path is unavailable");
        }
        return value;
    }
    private static int integer(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue())
            throw new IllegalArgumentException("Expected integer record position");
        return number.intValue();
    }
}
