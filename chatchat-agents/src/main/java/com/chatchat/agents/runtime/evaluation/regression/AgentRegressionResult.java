package com.chatchat.agents.runtime.evaluation;

import java.util.List;
import java.util.Map;

public record AgentRegressionResult(
    String contractVersion,
    String caseId,
    String status,
    RetrievalResult retrieval,
    EvidenceResult evidence,
    ReviewResult review,
    AnswerResult answer,
    Map<String, Double> metrics,
    List<String> notes
) {

    public static final String CONTRACT_VERSION = "agent_regression_result_v1";
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    public AgentRegressionResult {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public record RetrievalResult(
        boolean hit,
        int chunks,
        List<String> matched,
        List<String> missing
    ) {
        public RetrievalResult {
            matched = matched == null ? List.of() : List.copyOf(matched);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    public record EvidenceResult(
        double score,
        int chunksUsed,
        List<String> matchedKeywords,
        List<String> missingKeywords,
        EvidenceGraph graph
    ) {
        public EvidenceResult {
            matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
            missingKeywords = missingKeywords == null ? List.of() : List.copyOf(missingKeywords);
            graph = graph == null ? new EvidenceGraph(List.of(), List.of()) : graph;
        }
    }

    public record EvidenceGraph(
        List<String> entities,
        List<String> relations
    ) {
        public EvidenceGraph {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    public record ReviewResult(
        boolean passed,
        boolean reject,
        String reason,
        double score,
        boolean falseReject
    ) {
    }

    public record AnswerResult(
        boolean valid,
        List<String> matchedAny,
        List<String> expectedAny
    ) {
        public AnswerResult {
            matchedAny = matchedAny == null ? List.of() : List.copyOf(matchedAny);
            expectedAny = expectedAny == null ? List.of() : List.copyOf(expectedAny);
        }
    }
}
