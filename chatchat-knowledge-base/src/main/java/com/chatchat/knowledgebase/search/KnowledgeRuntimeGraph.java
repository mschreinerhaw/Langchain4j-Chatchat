package com.chatchat.knowledgebase.search;

import java.util.List;

public record KnowledgeRuntimeGraph(
    String documentId,
    String query,
    List<KnowledgeNode> nodes,
    List<KnowledgeEdge> edges
) {
    public KnowledgeRuntimeGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static KnowledgeRuntimeGraph empty(String documentId, String query) {
        return new KnowledgeRuntimeGraph(documentId, query, List.of(), List.of());
    }
}
