package com.chatchat.agents.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerAssemblyEngineTest {

    private final AnswerAssemblyEngine engine = new AnswerAssemblyEngine();

    @Test
    void plansFullAnswerWhenAGradeEvidenceIsAvailable() {
        AnswerAssemblyPolicy policy = engine.plan(List.of("""
            document_evidence_v1
            evidenceGovernancePolicy: {minAnswerEvidenceGrade: A, minAnswerCitations: 1}
            citation: doc://file-1#chunk=1
            evidenceGrade: A
            content:
            supported fact
            """));

        assertThat(policy.mode()).isEqualTo(AnswerAssemblyMode.FULL);
        assertThat(policy.minEvidenceGrade()).isEqualTo("A");
        assertThat(policy.minCitations()).isEqualTo(1);
    }

    @Test
    void plansPartialAnswerWhenOnlyWeakEvidenceIsAvailable() {
        AnswerAssemblyPolicy policy = engine.plan(List.of("""
            document_evidence_v1
            evidenceGovernancePolicy: {minAnswerEvidenceGrade: A}
            citation: doc://file-1#chunk=1
            evidenceGrade: B
            content:
            weakly supported fact
            """));

        assertThat(policy.mode()).isEqualTo(AnswerAssemblyMode.PARTIAL);
        assertThat(policy.missingInfo()).contains("required A-grade evidence is missing");
    }

    @Test
    void plansReviewWhenEvidenceConflictIsPresent() {
        AnswerAssemblyPolicy policy = engine.plan(List.of("""
            document_evidence_v1
            evidenceGovernancePolicy: {minAnswerEvidenceGrade: A, conflictPolicy: review_on_conflict}
            conflictDetected: true
            citation: doc://file-1#chunk=1
            evidenceGrade: A
            """));

        assertThat(policy.mode()).isEqualTo(AnswerAssemblyMode.REVIEW_REQUIRED);
        assertThat(policy.missingInfo()).contains("conflicting evidence must be explained before answering");
    }
}
