package com.chatchat.knowledgebase.search;

public record EvidenceGovernancePolicy(
    boolean citationRequired,
    DocumentEvidenceGrade minAnswerEvidenceGrade,
    int minAnswerCitations,
    int topKPerSection,
    int globalMaxChunks,
    String diversityConstraint,
    String conflictPolicy
) {
    public EvidenceGovernancePolicy {
        minAnswerEvidenceGrade = minAnswerEvidenceGrade == null ? DocumentEvidenceGrade.A : minAnswerEvidenceGrade;
        minAnswerCitations = Math.max(0, minAnswerCitations);
        topKPerSection = Math.max(0, topKPerSection);
        globalMaxChunks = Math.max(0, globalMaxChunks);
        diversityConstraint = diversityConstraint == null || diversityConstraint.isBlank()
            ? "prefer_distinct_sections"
            : diversityConstraint;
        conflictPolicy = conflictPolicy == null || conflictPolicy.isBlank()
            ? "review_on_conflict"
            : conflictPolicy;
    }

    public static EvidenceGovernancePolicy contentReady() {
        return new EvidenceGovernancePolicy(true, DocumentEvidenceGrade.A, 1, 2, 8, "prefer_distinct_sections", "review_on_conflict");
    }

    public static EvidenceGovernancePolicy needsExpansion(int maxSections, int maxChunks) {
        return new EvidenceGovernancePolicy(true, DocumentEvidenceGrade.A, 1, 2, Math.max(1, maxChunks), "prefer_distinct_sections", "review_on_conflict");
    }

    public static EvidenceGovernancePolicy noEvidence() {
        return new EvidenceGovernancePolicy(false, DocumentEvidenceGrade.A, 0, 0, 0, "none", "insufficient_evidence");
    }
}
