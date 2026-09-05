package com.chatchat.common.runtime.summary.analysis.semantic.model;

/** Source-neutral operations that a producer may explicitly authorize. */
public enum SemanticOperation {
    OBSERVE,
    AGGREGATE,
    DERIVE,
    COMPARE,
    RANK,
    TREND,
    INFER,
    PROXY;

    public static SemanticOperation from(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
