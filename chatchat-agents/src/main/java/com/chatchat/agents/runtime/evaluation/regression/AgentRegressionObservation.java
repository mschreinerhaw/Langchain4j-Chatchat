package com.chatchat.agents.runtime.evaluation;

import java.util.List;

public record AgentRegressionObservation(
    String caseId,
    List<String> retrievalTexts,
    int chunkCount,
    Double evidenceScore,
    int chunksUsed,
    Boolean reviewPassed,
    boolean reviewRejected,
    String reviewReason,
    Double reviewScore,
    Double reviewRejectRate,
    String answer
) {

    public AgentRegressionObservation {
        retrievalTexts = retrievalTexts == null ? List.of() : List.copyOf(retrievalTexts);
    }
}
