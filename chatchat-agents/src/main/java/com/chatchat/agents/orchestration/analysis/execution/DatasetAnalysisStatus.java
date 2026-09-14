package com.chatchat.agents.orchestration.analysis.execution;

/** Business-neutral lifecycle of one dataset participating in an execution. */
public enum DatasetAnalysisStatus {
    EXPECTED,
    ANALYZING,
    ANALYZED,
    FAILED,
    SKIPPED,
    TRUNCATED;

    public boolean terminal() {
        return this == ANALYZED || this == FAILED || this == SKIPPED || this == TRUNCATED;
    }
}
