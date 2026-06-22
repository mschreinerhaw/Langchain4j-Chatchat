package com.chatchat.agents.evidence;

import java.util.List;

public record EvidenceAnswerContract(
    String contractVersion,
    List<String> evidencePath,
    List<String> sqlLineage,
    boolean fromGraphOnly,
    boolean executable,
    EvidenceExecutionDecision decision
) {

    public static final String CONTRACT_VERSION = "evidence_answer_contract_v2";

    public EvidenceAnswerContract {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        evidencePath = evidencePath == null ? List.of() : List.copyOf(evidencePath);
        sqlLineage = sqlLineage == null ? List.of() : List.copyOf(sqlLineage);
        decision = decision == null ? EvidenceExecutionDecision.EMPTY_RESULT : decision;
    }
}
