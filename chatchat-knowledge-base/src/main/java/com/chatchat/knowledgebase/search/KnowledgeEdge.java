package com.chatchat.knowledgebase.search;

public record KnowledgeEdge(
    String fromNodeId,
    String toNodeId,
    KnowledgeRelationType type,
    String relation,
    double weight,
    String reason
) {
    public KnowledgeEdge {
        type = type == null ? KnowledgeRelationType.EVIDENCE_EVIDENCE : type;
        relation = relation == null ? "" : relation;
        weight = clamp(weight);
        reason = reason == null ? "" : reason;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
