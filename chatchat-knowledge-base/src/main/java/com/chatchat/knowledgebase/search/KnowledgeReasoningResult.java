package com.chatchat.knowledgebase.search;

import java.util.List;

public record KnowledgeReasoningResult(
    String query,
    List<KnowledgeEvidenceCluster> clusters,
    List<KnowledgeContradiction> contradictions,
    List<KnowledgeReasoningStep> steps,
    double confidence,
    String conclusionMode,
    List<String> missingInfo
) {
    public KnowledgeReasoningResult {
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        confidence = clamp(confidence);
        conclusionMode = conclusionMode == null || conclusionMode.isBlank() ? "INSUFFICIENT_EVIDENCE" : conclusionMode;
        missingInfo = missingInfo == null ? List.of() : List.copyOf(missingInfo);
    }

    public static KnowledgeReasoningResult empty(String query) {
        return new KnowledgeReasoningResult(
            query,
            List.of(),
            List.of(),
            List.of(),
            0.0D,
            "INSUFFICIENT_EVIDENCE",
            List.of("No traversal paths available for graph reasoning")
        );
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
