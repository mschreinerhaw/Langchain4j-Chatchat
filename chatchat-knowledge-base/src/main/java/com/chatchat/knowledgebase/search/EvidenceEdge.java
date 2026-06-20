package com.chatchat.knowledgebase.search;

public record EvidenceEdge(
    String sourceNodeId,
    String targetNodeId,
    EvidenceEdgeType type,
    double confidence,
    String reason
) {
    public EvidenceEdge {
        type = type == null ? EvidenceEdgeType.RELATED : type;
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
