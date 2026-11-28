package com.chatchat.api.runtime.coding.analysis;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Bounded observations of JSON values. Field names never determine their types or meanings. */
final class NativeSnapshotStatistics {
    private final Map<String, Column> columns = new LinkedHashMap<>();

    void observe(String field, Object value) {
        columns.computeIfAbsent(field, ignored -> new Column()).observe(value);
    }

    Map<String,Object> snapshot(long rows) {
        var result = new java.util.TreeMap<String,Object>();
        columns.forEach((name, column) -> result.put(name, column.snapshot(rows)));
        return result;
    }

    private static final class Column {
        final TreeSet<String> types = new TreeSet<>();
        long nonNull;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal min;
        BigDecimal max;
        boolean numericAvailable = true;

        void observe(Object value) {
            if (value == null || value instanceof com.fasterxml.jackson.databind.JsonNode j && j.isNull()) return;
            nonNull++;
            if (value instanceof Number number) {
                types.add("number");
                try {
                    var decimal = new BigDecimal(number.toString());
                    if (decimal.precision()>128 || Math.abs((long) decimal.scale())>128) {
                        numericAvailable = false;
                        return;
                    }
                    total = total.add(decimal);
                    min = min == null ? decimal : min.min(decimal);
                    max = max == null ? decimal : max.max(decimal);
                } catch (NumberFormatException unsupported) {
                    numericAvailable = false;
                }
            } else if (value instanceof Boolean) types.add("boolean");
            else if (value instanceof Map<?,?>) types.add("object");
            else if (value instanceof Iterable<?> || value.getClass().isArray()) types.add("array");
            else if (value instanceof CharSequence) types.add("string");
            else types.add("other");
        }

        Map<String,Object> snapshot(long rows) {
            var result = new LinkedHashMap<String,Object>();
            result.put("observedTypes", types);
            result.put("nonNull", nonNull);
            result.put("nullOrMissing", rows-nonNull);
            if(numericAvailable && min!=null && types.equals(java.util.Set.of("number")))
                result.put("numeric",Map.of("sum",total.toPlainString(),"min",min.toPlainString(),"max",max.toPlainString()));
            return result;
        }
    }
}
