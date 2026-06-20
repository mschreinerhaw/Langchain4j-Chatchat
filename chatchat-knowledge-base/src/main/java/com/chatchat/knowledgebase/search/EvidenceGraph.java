package com.chatchat.knowledgebase.search;

import java.util.List;

public record EvidenceGraph(
    String query,
    List<EvidenceNode> nodes,
    List<EvidenceEdge> edges,
    boolean conflictDetected
) {
    public EvidenceGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static EvidenceGraph empty(String query) {
        return new EvidenceGraph(query, List.of(), List.of(), false);
    }
}
