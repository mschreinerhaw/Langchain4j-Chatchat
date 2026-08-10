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
}
