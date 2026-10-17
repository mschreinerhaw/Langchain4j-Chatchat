package com.chatchat.common.mcp.python;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** Observed JSONL field types, not an authorization or output-contract validator. */
public final class JsonlStructuralSchema {
    private final SortedMap<String, TreeSet<String>> fields = new TreeMap<>();
    private long rows;

    public void accept(Map<String, String> rowTypes) {
        if (rowTypes == null) throw new IllegalArgumentException("JSONL field types are required");
        fields.forEach((name, types) -> { if (!rowTypes.containsKey(name)) types.add("MISSING"); });
        rowTypes.forEach((name, type) -> {
            var types = fields.computeIfAbsent(name, ignored -> {
                var observed = new TreeSet<String>();
                if (rows > 0) observed.add("MISSING");
                return observed;
            });
            types.add(type);
        });
        rows++;
    }

    /** Homogeneous files retain their legacy field-to-single-type representation. */
    public Map<String, String> fieldTypes() {
        var result = new TreeMap<String, String>();
        fields.forEach((name, types) -> result.put(name, String.join("|", types)));
        return Collections.unmodifiableSortedMap(result);
    }
}
