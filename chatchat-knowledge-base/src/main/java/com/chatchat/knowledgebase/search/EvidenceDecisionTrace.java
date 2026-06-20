package com.chatchat.knowledgebase.search;

import java.util.List;

public record EvidenceDecisionTrace(
    String contractVersion,
    String policyName,
    String selectedRuleId,
    List<EvidenceDecisionTraceStep> steps,
    boolean replayable
) {
    public static final String CONTRACT_VERSION = "evidence_decision_trace_v1";

    public EvidenceDecisionTrace {
        contractVersion = contractVersion == null || contractVersion.isBlank()
            ? CONTRACT_VERSION
            : contractVersion;
        policyName = policyName == null || policyName.isBlank()
            ? "default_evidence_decision_policy"
            : policyName;
        selectedRuleId = selectedRuleId == null ? "" : selectedRuleId;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static EvidenceDecisionTrace empty() {
        return new EvidenceDecisionTrace(
            CONTRACT_VERSION,
            "default_evidence_decision_policy",
            "",
            List.of(),
            true
        );
    }
}
