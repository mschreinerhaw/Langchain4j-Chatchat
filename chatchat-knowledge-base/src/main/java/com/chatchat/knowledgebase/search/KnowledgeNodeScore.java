package com.chatchat.knowledgebase.search;

public record KnowledgeNodeScore(
    String nodeId,
    KnowledgeNodeType nodeType,
    double score,
    String reason
) {
    public KnowledgeNodeScore {
        nodeType = nodeType == null ? KnowledgeNodeType.EVIDENCE : nodeType;
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
