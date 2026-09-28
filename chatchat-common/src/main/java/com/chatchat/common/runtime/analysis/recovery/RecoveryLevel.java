package com.chatchat.common.runtime.analysis.recovery;

/** Ordered recovery ladder. Lower levels must be preferred before broader retrieval. */
public enum RecoveryLevel {
    L1_CHUNK_EXPANSION,
    L2_PARENT_SECTION,
    L3_ADJACENT_SECTION,
    L4_SAME_DOCUMENT_SEARCH,
    L5_ORIGINAL_DOCUMENT,
    L6_CROSS_DOCUMENT_SEARCH,
    L7_EXTERNAL_SOURCE
}
