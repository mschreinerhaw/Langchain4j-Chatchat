package com.chatchat.common.runtime.analysis.workflow;

import java.util.Locale;

/** Placement policy for problem-analysis execution. */
public enum AnalysisExecutionMode {
    /** Execute inside the current Runtime invocation or Temporal Activity. */
    INLINE,
    /** Submit an independently durable workflow through the Runtime workflow port. */
    DURABLE;

    public static AnalysisExecutionMode from(Object value) {
        if (value == null) return INLINE;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return INLINE;
        }
    }
}
