package com.chatchat.agents.evidence.graph;

public record EvidenceGraphEdge(
    String fromNodeId,
    String toNodeId,
    EvidenceGraphEdgeType type,
    double weight,
    String reasoning
) {
}
