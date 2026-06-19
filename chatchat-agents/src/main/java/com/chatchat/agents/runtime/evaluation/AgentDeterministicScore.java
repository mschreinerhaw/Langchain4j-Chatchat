package com.chatchat.agents.runtime.evaluation;

import java.util.List;
import java.util.Map;

public record AgentDeterministicScore(
    double score,
    double keywordCoverage,
    double entityCoverage,
    double connectorCoverage,
    double chunkCoverage,
    List<String> matchedKeywords,
    List<String> missingKeywords,
    List<String> matchedEntities,
    List<String> matchedConnectors,
    AgentRegressionResult.EvidenceGraph graph,
    Map<String, Double> metrics
) {

    public AgentDeterministicScore {
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
        missingKeywords = missingKeywords == null ? List.of() : List.copyOf(missingKeywords);
        matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
        matchedConnectors = matchedConnectors == null ? List.of() : List.copyOf(matchedConnectors);
        graph = graph == null ? new AgentRegressionResult.EvidenceGraph(List.of(), List.of()) : graph;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
