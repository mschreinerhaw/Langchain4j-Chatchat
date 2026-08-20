package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceBasedAssetCandidateEvaluatorTest {

    @Test
    void selectsOnlyIdsReturnedByAssetDiscovery() {
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedAssetCandidateEvaluator().evaluate(
                Map.of("assets", List.of(
                    Map.of("asset", Map.of("id", "prod-a", "name", "A")),
                    Map.of("asset", Map.of("id", "prod-b", "name", "B"))
                )),
                Map.of("selectedAssetIds", List.of("prod-b"), "rejectedAssetIds", List.of("prod-a"))
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.selectedIds()).containsExactly("prod-b");
        assertThat(evaluation.output().toString())
            .contains("runtime_asset_selection.v1", "prod-b")
            .doesNotContain("name=A");
    }

    @Test
    void refusesInventedAssetId() {
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedAssetCandidateEvaluator().evaluate(
                Map.of("assets", List.of(Map.of("asset", Map.of("id", "authorized")))),
                Map.of("selectedAssetIds", List.of("invented"))
            );

        assertThat(evaluation.applied()).isFalse();
    }

    @Test
    void preservesUniqueCandidateWhenReviewContainsNoCandidateLevelDecision() {
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedAssetCandidateEvaluator().evaluate(
                Map.of("assets", List.of(Map.of("asset", Map.of("id", "oracle-risk", "name", "Oracle")))),
                Map.of("reason", "downstream evidence is still missing")
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.selectedIds()).containsExactly("oracle-risk");
        assertThat(evaluation.output().toString()).contains("selectionAuthority=runtime_unique_candidate");
    }

    @Test
    void honorsExplicitRejectionOfUniqueCandidate() {
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedAssetCandidateEvaluator().evaluate(
                Map.of("assets", List.of(Map.of("asset", Map.of("id", "mysql-dev")))),
                Map.of("rejectedAssetIds", List.of("mysql-dev"))
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.selectedCount()).isZero();
    }

    @Test
    void requiresCandidateLevelDecisionWhenMultipleAssetsRemain() {
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedAssetCandidateEvaluator().evaluate(
                Map.of("assets", List.of(
                    Map.of("asset", Map.of("id", "oracle-risk")),
                    Map.of("asset", Map.of("id", "mysql-dev"))
                )),
                Map.of("reason", "request is not complete")
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.selectedCount()).isZero();
    }

    @Test
    void doesNotAdmitUniqueCandidateWhenModelReviewerWasUnavailable() {
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedAssetCandidateEvaluator().evaluate(
                Map.of("assets", List.of(Map.of("asset", Map.of("id", "oracle-risk")))),
                Map.of("toolResultReviewUnavailable", true)
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.selectedCount()).isZero();
    }

    @Test
    void usesToolDeclaredSelectionWhenReviewerIsUnavailable() {
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedAssetCandidateEvaluator().evaluate(
                Map.of(
                    "queryIr", Map.of("asset", Map.of(
                        "selected", Map.of("id", "oracle-risk"))),
                    "assets", List.of(
                        Map.of("asset", Map.of("id", "oracle-risk")),
                        Map.of("asset", Map.of("id", "mysql-dev"))
                    )
                ),
                Map.of("toolResultReviewUnavailable", true)
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.selectedIds()).containsExactly("oracle-risk");
        assertThat(evaluation.output().toString())
            .contains("selectionAuthority=runtime_tool_declared_selection")
            .doesNotContain("id=mysql-dev");
    }
}
