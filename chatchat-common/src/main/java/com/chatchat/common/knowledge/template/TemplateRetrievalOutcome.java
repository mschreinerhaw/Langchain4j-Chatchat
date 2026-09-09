package com.chatchat.common.knowledge.template;

/** Program-owned retrieval outcome. Each value has a distinct recovery action. */
public enum TemplateRetrievalOutcome {
    SELECTED,
    PAGE_EXHAUSTED_HAS_MORE,
    SCOPE_EXHAUSTED_NO_MATCH,
    SCOPE_INSUFFICIENT_SUSPECTED,
    PARAMS_UNRESOLVABLE,
    EVIDENCE_INSUFFICIENT_POST_EXECUTION,
    EXECUTION_FAILED,
    CAPABILITY_EXCEEDED
}
