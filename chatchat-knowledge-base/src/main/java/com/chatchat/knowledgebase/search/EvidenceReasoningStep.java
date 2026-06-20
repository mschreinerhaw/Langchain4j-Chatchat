package com.chatchat.knowledgebase.search;

import java.util.List;

public record EvidenceReasoningStep(
    String step,
    String description,
    List<String> evidenceNodeIds,
    double confidence
) {
    public EvidenceReasoningStep {
        step = step == null || step.isBlank() ? "support" : step;
        description = description == null ? "" : description;
        evidenceNodeIds = evidenceNodeIds == null ? List.of() : List.copyOf(evidenceNodeIds);
        confidence = clamp(confidence);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
