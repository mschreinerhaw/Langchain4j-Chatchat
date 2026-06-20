package com.chatchat.knowledgebase.search;

import java.util.List;

public record KnowledgeTraversalPath(
    String rootSectionId,
    String evidenceNodeId,
    List<String> nodeIds,
    double score,
    String reason
) {
    public KnowledgeTraversalPath {
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        score = clamp(score);
        reason = reason == null ? "" : reason;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
