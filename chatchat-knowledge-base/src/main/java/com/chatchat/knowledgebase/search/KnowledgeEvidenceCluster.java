package com.chatchat.knowledgebase.search;

import java.util.List;

public record KnowledgeEvidenceCluster(
    String sectionNodeId,
    List<String> evidenceNodeIds,
    double aggregateScore,
    String reason
) {
    public KnowledgeEvidenceCluster {
        evidenceNodeIds = evidenceNodeIds == null ? List.of() : List.copyOf(evidenceNodeIds);
        aggregateScore = clamp(aggregateScore);
        reason = reason == null ? "" : reason;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
