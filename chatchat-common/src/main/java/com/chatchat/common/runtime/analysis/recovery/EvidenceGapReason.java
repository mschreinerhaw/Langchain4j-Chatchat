package com.chatchat.common.runtime.analysis.recovery;

/** Runtime-owned structural reasons why an evidence bundle cannot proceed to final synthesis. */
public enum EvidenceGapReason {
    SOURCE_TRUNCATED,
    SECTION_INCOMPLETE,
    SEQUENCE_INCOMPLETE,
    CLAIM_UNSUPPORTED,
    LOW_COVERAGE,
    CONFLICTING_EVIDENCE,
    SOURCE_NOT_AUTHORITATIVE,
    MISSING_PRIMARY_SOURCE,
    MISSING_CONTEXT,
    RETRIEVAL_EMPTY,
    RETRIEVAL_WEAK,
    DATA_INCOMPLETE
}
