package com.chatchat.common.runtime.analysis.recovery;

import java.util.List;

/** Structured, auditable input to an evidence-recovery workflow. */
public record EvidenceGap(
    EvidenceGapReason reason,
    String targetClaimId,
    String documentId,
    String sectionId,
    double currentCoverage,
    double requiredCoverage,
    boolean sequenceSensitive,
    boolean truncated,
    List<String> missingEvidence
) {
    public EvidenceGap {
        if (reason == null) throw new IllegalArgumentException("evidence gap reason is required");
        currentCoverage = bounded(currentCoverage);
        requiredCoverage = bounded(requiredCoverage);
        missingEvidence = missingEvidence == null ? List.of() : missingEvidence.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static double bounded(double value) {
        return Math.max(0D, Math.min(1D, value));
    }
}
