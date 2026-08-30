package com.chatchat.common.runtime.summary;

/** Canonical per-assignment analysis procedure shared by Worker and Driver implementations. */
public enum DataAnalysisExecutionStep {
    VALIDATE_ASSIGNMENT,
    ANALYZE_ASSIGNED_EVIDENCE,
    PRODUCE_SCOPED_SUMMARY,
    RECONCILE_INPUT_LINEAGE
}
