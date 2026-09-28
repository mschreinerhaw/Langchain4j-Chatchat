package com.chatchat.common.runtime.analysis.recovery;

public enum RecoveryStrategy {
    CONTEXT_EXPANSION,
    SECTION_EXPANSION,
    ADJACENT_SECTION_SEARCH,
    CLAIM_TARGETED_SEARCH,
    CROSS_SOURCE_VERIFY,
    ORIGINAL_DOCUMENT_FETCH,
    QUERY_REWRITE_HYBRID_RETRIEVAL
}
