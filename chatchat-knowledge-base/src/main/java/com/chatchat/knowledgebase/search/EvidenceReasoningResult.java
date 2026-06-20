package com.chatchat.knowledgebase.search;

import java.util.List;

public record EvidenceReasoningResult(
    String contractVersion,
    String query,
    EvidenceGraph graph,
    List<EvidenceReasoningStep> reasoningChain,
    double confidence,
    boolean conflictDetected,
    String conclusionMode,
    List<String> missingInfo
) {
    public static final String CONTRACT_VERSION = "evidence_reasoning_v1";

    public EvidenceReasoningResult {
        contractVersion = contractVersion == null || contractVersion.isBlank()
            ? CONTRACT_VERSION
            : contractVersion;
        graph = graph == null ? EvidenceGraph.empty(query) : graph;
        reasoningChain = reasoningChain == null ? List.of() : List.copyOf(reasoningChain);
        confidence = clamp(confidence);
        conclusionMode = conclusionMode == null || conclusionMode.isBlank()
            ? "INSUFFICIENT_EVIDENCE"
            : conclusionMode;
        missingInfo = missingInfo == null ? List.of() : List.copyOf(missingInfo);
    }

    public static EvidenceReasoningResult empty(String query) {
        return new EvidenceReasoningResult(
            CONTRACT_VERSION,
            query,
            EvidenceGraph.empty(query),
            List.of(),
            0.0D,
            false,
            "INSUFFICIENT_EVIDENCE",
            List.of("No evidence nodes available for reasoning")
        );
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
