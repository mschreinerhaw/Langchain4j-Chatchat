package com.chatchat.agents.runtime.evaluation;

import java.util.List;
import java.util.Map;

public record AgentEvaluationCase(
    String question,
    List<String> expectedEvidence,
    List<String> expectedKeywords,
    boolean mustHaveCitation,
    List<RetrievalExpectation> expectedRetrieval,
    List<ToolExpectation> expectedTools,
    Thresholds thresholds
) {

    public AgentEvaluationCase(String question,
                               List<String> expectedEvidence,
                               List<String> expectedKeywords,
                               boolean mustHaveCitation) {
        this(question, expectedEvidence, expectedKeywords, mustHaveCitation,
            List.of(), List.of(), Thresholds.strict());
    }

    public AgentEvaluationCase {
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
        expectedRetrieval = expectedRetrieval == null ? List.of() : List.copyOf(expectedRetrieval);
        expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        thresholds = thresholds == null ? Thresholds.strict() : thresholds;
    }

    /** Gold relevance label. Either refId or content terms may identify a relevant result. */
    public record RetrievalExpectation(String refId, List<String> mustContainAny, Integer maxRank) {
        public RetrievalExpectation {
            mustContainAny = mustContainAny == null ? List.of() : List.copyOf(mustContainAny);
            maxRank = maxRank == null || maxRank < 1 ? Integer.MAX_VALUE : maxRank;
        }
    }

    /** Expected tool and the argument subset that must be passed to it. */
    public record ToolExpectation(String toolName, Map<String, Object> expectedArguments) {
        public ToolExpectation {
            expectedArguments = expectedArguments == null ? Map.of() : Map.copyOf(expectedArguments);
        }
    }

    public record Thresholds(double minRetrievalScore,
                             double minToolSelectionScore,
                             double minParameterAccuracy,
                             double minEvidenceCompleteness,
                             double minOverallScore) {
        public Thresholds {
            minRetrievalScore = bounded(minRetrievalScore);
            minToolSelectionScore = bounded(minToolSelectionScore);
            minParameterAccuracy = bounded(minParameterAccuracy);
            minEvidenceCompleteness = bounded(minEvidenceCompleteness);
            minOverallScore = bounded(minOverallScore);
        }

        public static Thresholds strict() {
            return new Thresholds(1.0D, 1.0D, 1.0D, 1.0D, 1.0D);
        }

        private static double bounded(double value) {
            return Math.max(0.0D, Math.min(1.0D, value));
        }
    }
}
