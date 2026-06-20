package com.chatchat.knowledgebase.search;

import java.util.Map;

public record EvidenceDecisionTraceStep(
    int priority,
    String ruleId,
    EvidenceDecisionAction candidateAction,
    boolean matched,
    String reason,
    Map<String, Object> facts
) {
    public EvidenceDecisionTraceStep {
        priority = Math.max(0, priority);
        ruleId = ruleId == null || ruleId.isBlank() ? "unknown_rule" : ruleId;
        candidateAction = candidateAction == null ? EvidenceDecisionAction.REVIEW_REQUIRED : candidateAction;
        reason = reason == null ? "" : reason;
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
