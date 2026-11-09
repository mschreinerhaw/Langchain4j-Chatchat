package com.chatchat.api.runtime.coding.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Explicit result filenames, resolved only within the existing results archive. */
public final class CodingRequiredArtifacts {
    private CodingRequiredArtifacts() {}

    public static List<String> read(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof Collection<?> names) || names.size() > 8)
            throw new IllegalArgumentException("At most eight required result artifacts are supported");
        var result = new ArrayList<String>();
        for (Object name : names) {
            if (!(name instanceof String file) || file.length() > 96
                    || !file.matches("[A-Za-z0-9_-]+\\.(md|png|xlsx|json|jsonl|py)") || result.contains(file))
                throw new IllegalArgumentException("Required artifacts must be unique supported result basenames");
            result.add(file);
        }
        return List.copyOf(result);
    }

    static List<String> files(Object value) {
        var files = new ArrayList<String>();
        files.add("rows.jsonl");
        for (String file : read(value)) if (!files.contains(file)) files.add(file);
        return List.copyOf(files);
    }
}
