package com.chatchat.knowledgebase.search;

import java.util.List;

public record KnowledgeTraversalResult(
    String query,
    List<KnowledgeNodeScore> nodeScores,
    List<KnowledgeTraversalPath> paths,
    int maxSectionDepth,
    int selectedSections,
    int selectedEvidence
) {
    public KnowledgeTraversalResult {
        nodeScores = nodeScores == null ? List.of() : List.copyOf(nodeScores);
        paths = paths == null ? List.of() : List.copyOf(paths);
        maxSectionDepth = Math.max(0, maxSectionDepth);
        selectedSections = Math.max(0, selectedSections);
        selectedEvidence = Math.max(0, selectedEvidence);
    }

    public static KnowledgeTraversalResult empty(String query) {
        return new KnowledgeTraversalResult(query, List.of(), List.of(), 0, 0, 0);
    }
}
