package com.chatchat.agents.runtime.evaluation;

import java.util.List;
import java.util.Map;

public record AgentEvaluationReport(
    String contractVersion,
    String runId,
    String question,
    boolean passed,
    Map<String, Double> metrics,
    Map<String, QualityDimension> dimensions,
    List<String> matchedEvidence,
    List<String> missingEvidence,
    List<String> matchedKeywords,
    List<String> missingKeywords,
    List<String> notes
) {

    public static final String CONTRACT_VERSION = "agent_evaluation_v2";

    public AgentEvaluationReport {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        matchedEvidence = matchedEvidence == null ? List.of() : List.copyOf(matchedEvidence);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
        missingKeywords = missingKeywords == null ? List.of() : List.copyOf(missingKeywords);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public record QualityDimension(double score,
                                   boolean passed,
                                   int expectedCount,
                                   int actualCount,
                                   List<String> details) {
        public QualityDimension {
            score = Math.max(0.0D, Math.min(1.0D, score));
            details = details == null ? List.of() : List.copyOf(details);
        }
    }
}
