package com.chatchat.agents.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAnswerGroundingGuardTest {

    private final EvidenceAnswerGroundingGuard guard = new EvidenceAnswerGroundingGuard();

    @Test
    void groundsAnswerWithDocumentAndWebCitations() {
        EvidenceAnswerGroundingGuard.GroundingResult result = guard.guard(
            "配置变更后需要重启服务 doc://file-1#chunk=3，并可由网页核验 web://example.com/a#result=1。",
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://file-1#chunk=3
                content:
                restart service
                [Evidence 2]
                type: WEB
                citation: web://example.com/a#result=1
                content:
                external verification
                """)
        );

        assertThat(result.contractVersion()).isEqualTo("evidence_answer_v1");
        assertThat(result.groundingStatus()).isEqualTo("grounded");
        assertThat(result.evidenceAnswer().confidence()).isEqualTo("high");
        assertThat(result.evidenceAnswer().citations())
            .extracting(item -> item.get("refId"))
            .containsExactly("doc://file-1#chunk=3", "web://example.com/a#result=1");
    }

    @Test
    void marksMissingCitationAsNeedsReview() {
        EvidenceAnswerGroundingGuard.GroundingResult result = guard.guard(
            "配置变更后需要重启服务。",
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://file-1#chunk=3
                content:
                restart service
                """)
        );

        assertThat(result.groundingStatus()).isEqualTo("needs_review");
        assertThat(result.evidenceAnswer().confidence()).isEqualTo("low");
        assertThat(result.evidenceAnswer().missingInfo()).contains("answer citations are missing");
    }

    @Test
    void marksUnknownCitationAsNeedsReview() {
        EvidenceAnswerGroundingGuard.GroundingResult result = guard.guard(
            "配置变更后需要重启服务 doc://file-2#chunk=9。",
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://file-1#chunk=3
                content:
                restart service
                """)
        );

        assertThat(result.groundingStatus()).isEqualTo("needs_review");
        assertThat(result.evidenceAnswer().missingInfo())
            .contains("answer cites evidence not returned by evidence tools");
    }
    @Test
    void requiresAGradeDocumentEvidenceWhenGovernancePolicyIsPresent() {
        EvidenceAnswerGroundingGuard.GroundingResult result = guard.guard(
            "The accounting threshold is 1bp doc://file-1#chunk=4.",
            List.of("""
                document_evidence_v1
                evidenceGovernancePolicy: {minAnswerEvidenceGrade: A, citationRequired: true}
                [Evidence 1]
                citation: doc://file-1#chunk=4
                evidenceGrade: B
                content:
                threshold is 1bp
                """)
        );

        assertThat(result.groundingStatus()).isEqualTo("needs_review");
        assertThat(result.evidenceAnswer().missingInfo())
            .contains("answer must cite at least one A-grade evidence");
    }

    @Test
    void marksEvidenceConflictAsNeedsReview() {
        EvidenceAnswerGroundingGuard.GroundingResult result = guard.guard(
            "The accounting threshold is 1bp doc://file-1#chunk=4.",
            List.of("""
                document_evidence_v1
                evidenceGovernancePolicy: {minAnswerEvidenceGrade: A, conflictPolicy: review_on_conflict}
                conflictDetected: true
                [Evidence 1]
                citation: doc://file-1#chunk=4
                evidenceGrade: A
                content:
                threshold is 1bp
                """)
        );

        assertThat(result.groundingStatus()).isEqualTo("needs_review");
        assertThat(result.evidenceAnswer().missingInfo())
            .contains("evidence conflict requires review before answer assembly");
    }
}
