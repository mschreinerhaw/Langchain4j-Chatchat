package com.chatchat.knowledgebase.search;

public record EvidenceDecisionPolicy(
    double minAnswerConfidence,
    double minPartialConfidence,
    int minAnswerEvidenceNodes,
    boolean requireAgradeEvidence,
    boolean reviewOnConflict,
    boolean expandOnTitleOnly,
    boolean clarifyOnMissingInfo
) {
    public EvidenceDecisionPolicy {
        minAnswerConfidence = clamp(defaultIfInvalid(minAnswerConfidence, 0.72D));
        minPartialConfidence = clamp(defaultIfInvalid(minPartialConfidence, 0.55D));
        minAnswerEvidenceNodes = Math.max(1, minAnswerEvidenceNodes);
    }

    public static EvidenceDecisionPolicy defaults() {
        return new EvidenceDecisionPolicy(
            0.72D,
            0.55D,
            1,
            true,
            true,
            true,
            true
        );
    }

    private static double defaultIfInvalid(double value, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : value;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
