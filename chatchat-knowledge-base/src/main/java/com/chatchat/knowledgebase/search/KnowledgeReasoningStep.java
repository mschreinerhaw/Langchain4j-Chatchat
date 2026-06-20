package com.chatchat.knowledgebase.search;

import java.util.List;

public record KnowledgeReasoningStep(
    String step,
    String description,
    List<String> nodeIds,
    double confidence
) {
    public KnowledgeReasoningStep {
        step = step == null ? "" : step;
        description = description == null ? "" : description;
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        confidence = clamp(confidence);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
