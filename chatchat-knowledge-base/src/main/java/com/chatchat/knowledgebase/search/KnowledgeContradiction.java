package com.chatchat.knowledgebase.search;

public record KnowledgeContradiction(
    String leftNodeId,
    String rightNodeId,
    String relation,
    double confidence,
    String reason
) {
    public KnowledgeContradiction {
        relation = relation == null ? "CONTRADICTS" : relation;
        confidence = clamp(confidence);
        reason = reason == null ? "" : reason;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
