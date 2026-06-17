package com.chatchat.agents.runtime.evaluation;

import java.util.List;
import java.util.Map;

public record AgentEvaluationReport(
    String contractVersion,
    String runId,
    String question,
    boolean passed,
    Map<String, Double> metrics,
    List<String> matchedEvidence,
    List<String> missingEvidence,
    List<String> matchedKeywords,
    List<String> missingKeywords,
    List<String> notes
) {

    public static final String CONTRACT_VERSION = "agent_evaluation_v1";

    public AgentEvaluationReport {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        matchedEvidence = matchedEvidence == null ? List.of() : List.copyOf(matchedEvidence);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
        missingKeywords = missingKeywords == null ? List.of() : List.copyOf(missingKeywords);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
