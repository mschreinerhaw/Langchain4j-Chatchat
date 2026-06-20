package com.chatchat.knowledgebase.search;

public record SectionEdge(
    String fromSectionId,
    String toSectionId,
    SectionEdgeType type,
    double weight,
    String reason
) {
    public SectionEdge {
        type = type == null ? SectionEdgeType.RELATED : type;
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
