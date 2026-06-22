package com.chatchat.agents.evidence;

public record EvidenceGraphEdge(
    String fromNodeId,
    String toNodeId,
    EvidenceGraphEdgeType type,
    double weight,
    String reasoning
) {
}
