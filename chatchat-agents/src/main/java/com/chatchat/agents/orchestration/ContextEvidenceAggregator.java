package com.chatchat.agents.orchestration;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic structural aggregation for oversized generic tool results.
 */
final class ContextEvidenceAggregator {

    private static final int MAX_DEPTH = 10;
    private static final int TOP_VALUES = 10;
    private static final int REPRESENTATIVE_ROWS = 5;
    private static final int INLINE_TEXT_CHARS = 1_000;
    private static final int TEXT_EDGE_CHARS = 400;

    Object aggregate(Object value) {
        return aggregate(value, 0);
    }

    private Object aggregate(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return aggregateText(text.toString());
        }
        if (value.getClass().isRecord()) {
            return aggregate(recordMap(value), depth);
        }
        if (depth >= MAX_DEPTH) {
            return shape(value);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("_aggregation", "STRUCTURED_MAP");
            result.put("_fieldCount", map.size());
            result.put("_assessmentCapability", "LIMITED_UNKNOWN_CONTRACT");
            result.put("_lossNotice", "Generic aggregation preserves bounded semantic samples but is not a schema-aware completeness projection.");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), aggregate(entry.getValue(), depth + 1));
            }
            return result;
        }
        List<?> values = listValue(value);
        if (values != null) {
            return aggregateList(values, depth);
        }
        return aggregateText(String.valueOf(value));
    }

    private Object aggregateList(List<?> values, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_aggregation", "COLLECTION_PROFILE");
        result.put("count", values.size());
        if (values.isEmpty()) {
            return result;
        }
        List<Map<?, ?>> rows = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> row) {
                rows.add(row);
            } else if (value != null && value.getClass().isRecord()) {
                rows.add(recordMap(value));
            }
        }
        if (rows.size() == values.size()) {
            result.put("rowShape", aggregateRows(rows, depth + 1));
            result.put("representativeRows", rows.stream()
                .limit(REPRESENTATIVE_ROWS)
                .map(row -> aggregate(row, depth + 1))
                .toList());
            return result;
        }
        result.put("valueProfile", scalarProfile(values));
        return result;
    }

    private Map<String, Object> aggregateRows(List<Map<?, ?>> rows, int depth) {
        Map<String, List<Object>> valuesByField = new TreeMap<>();
        for (Map<?, ?> row : rows) {
            for (Map.Entry<?, ?> entry : row.entrySet()) {
                valuesByField.computeIfAbsent(String.valueOf(entry.getKey()), ignored -> new ArrayList<>())
                    .add(entry.getValue());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", rows.size());
        result.put("fields", valuesByField.keySet());
        Map<String, Object> fieldProfiles = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : valuesByField.entrySet()) {
            List<Object> values = entry.getValue();
            if (values.stream().allMatch(this::scalar)) {
                fieldProfiles.put(entry.getKey(), scalarProfile(values));
            } else {
                fieldProfiles.put(entry.getKey(), aggregate(values, depth + 1));
            }
        }
        result.put("fieldProfiles", fieldProfiles);
        return result;
    }

    private Map<String, Object> scalarProfile(List<?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        long nullCount = values.stream().filter(value -> value == null).count();
        List<?> nonNull = values.stream().filter(value -> value != null).toList();
        result.put("observedCount", values.size());
        result.put("nullCount", nullCount);
        if (nonNull.isEmpty()) {
            return result;
        }
        if (nonNull.stream().allMatch(Number.class::isInstance)) {
            List<Double> numbers = nonNull.stream()
                .map(Number.class::cast)
                .map(Number::doubleValue)
                .toList();
            result.put("type", "NUMBER");
            result.put("min", numbers.stream().min(Double::compareTo).orElse(0d));
            result.put("max", numbers.stream().max(Double::compareTo).orElse(0d));
            result.put("average", numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0d));
            return result;
        }
        Map<String, Long> distribution = new TreeMap<>();
        long minChars = Long.MAX_VALUE;
        long maxChars = 0;
        long totalChars = 0;
        for (Object value : nonNull) {
            String text = String.valueOf(value);
            minChars = Math.min(minChars, text.length());
            maxChars = Math.max(maxChars, text.length());
            totalChars += text.length();
            if (text.length() <= 160) {
                distribution.merge(text, 1L, Long::sum);
            }
        }
        result.put("type", nonNull.stream().allMatch(Boolean.class::isInstance) ? "BOOLEAN" : "TEXT");
        result.put("minChars", minChars == Long.MAX_VALUE ? 0 : minChars);
        result.put("maxChars", maxChars);
        result.put("averageChars", totalChars / (double) nonNull.size());
        result.put("distinctShortValueCount", distribution.size());
        result.put("topValues", distribution.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(TOP_VALUES)
            .map(entry -> Map.of("value", entry.getKey(), "count", entry.getValue()))
            .toList());
        return result;
    }

    private Object aggregateText(String text) {
        if (text.length() <= INLINE_TEXT_CHARS) {
            return text;
        }
        return Map.of(
            "_aggregation", "TEXT_PROFILE",
            "chars", text.length(),
            "lines", text.isEmpty() ? 0 : text.lines().count(),
            "blank", text.isBlank(),
            "truncated", true,
            "head", text.substring(0, Math.min(TEXT_EDGE_CHARS, text.length())),
            "tail", text.substring(Math.max(0, text.length() - TEXT_EDGE_CHARS))
        );
    }

    private boolean scalar(Object value) {
        return value == null
            || value instanceof CharSequence
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Enum<?>;
    }

    private List<?> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(values::add);
            return values;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(Array.get(value, index));
            }
            return values;
        }
        return null;
    }

    private Object shape(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Map.of("_aggregation", "NESTED_MAP_SHAPE", "fieldCount", map.size(), "fields",
                map.keySet().stream().map(String::valueOf).sorted().toList());
        }
        List<?> values = listValue(value);
        if (values != null) {
            return Map.of("_aggregation", "NESTED_COLLECTION_SHAPE", "count", values.size());
        }
        return aggregateText(String.valueOf(value));
    }

    private Map<String, Object> recordMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null || !value.getClass().isRecord()) {
            return result;
        }
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                result.put(component.getName(), component.getAccessor().invoke(value));
            } catch (ReflectiveOperationException ignored) {
                result.put(component.getName(), "[unavailable]");
            }
        }
        return result;
    }
}
