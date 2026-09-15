package com.chatchat.agents.assessment;

/** Aggregated usability of the evidence retained by one analysis run. */
public enum EvidenceGrade {
    SUFFICIENT,
    PARTIAL_USABLE,
    LIMITED,
    INSUFFICIENT;

    public boolean synthesisAllowed() {
        return this != INSUFFICIENT;
    }
}
