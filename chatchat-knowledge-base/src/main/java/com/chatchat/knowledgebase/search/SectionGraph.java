package com.chatchat.knowledgebase.search;

import java.util.List;

public record SectionGraph(
    String documentId,
    String query,
    List<SectionNode> nodes,
    List<SectionEdge> edges
) {
    public SectionGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static SectionGraph empty(String documentId, String query) {
        return new SectionGraph(documentId, query, List.of(), List.of());
    }
}
