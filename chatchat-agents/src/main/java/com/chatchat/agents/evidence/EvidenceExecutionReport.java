package com.chatchat.agents.evidence;

import java.util.List;

public record EvidenceExecutionReport(
    String contractVersion,
    EvidenceExecutionDecision decision,
    EvidencePath selectedPath,
    EvidenceAnswerContract answerContract,
    List<String> reasons
) {

    public static final String CONTRACT_VERSION = "evidence_os_execution_v2";

    public EvidenceExecutionReport {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        decision = decision == null ? EvidenceExecutionDecision.EMPTY_RESULT : decision;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
