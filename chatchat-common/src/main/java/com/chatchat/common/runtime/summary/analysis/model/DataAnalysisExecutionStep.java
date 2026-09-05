package com.chatchat.common.runtime.summary.analysis.model;

/** Canonical per-assignment analysis procedure shared by Worker and Driver implementations. */
public enum DataAnalysisExecutionStep {
    VALIDATE_ASSIGNMENT,
    ANALYZE_ASSIGNED_EVIDENCE,
    PRODUCE_SCOPED_SUMMARY,
    RECONCILE_INPUT_LINEAGE;

    public static java.util.List<DataAnalysisExecutionStep> procedure() {
        return java.util.List.of(values());
    }
}
