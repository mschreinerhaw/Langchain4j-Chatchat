package com.chatchat.agents.runtime.evaluation;

import java.util.List;

public record AgentEvaluationCase(
    String question,
    List<String> expectedEvidence,
    List<String> expectedKeywords,
    boolean mustHaveCitation
) {

    public AgentEvaluationCase {
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
    }
}
