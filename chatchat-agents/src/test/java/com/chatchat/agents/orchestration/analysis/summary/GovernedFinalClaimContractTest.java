package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedFinalClaimContractTest {

    private final GovernedFinalClaimContract contract = new GovernedFinalClaimContract();

    @Test
    void publishesOnlySelectedAdmittedClaimText() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["claim-1"],"sections":[]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isTrue();
        assertThat(projection.markdown()).contains("返回值为 42");
        assertThat(projection.markdown()).doesNotContain("模型新增结论");
    }

    @Test
    void unknownClaimIdFallsBackToAdmittedLedger() {
        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(summary()));

        GovernedFinalClaimContract.Projection projection = contract.project("""
            {"schemaVersion":"governed_final_claim_selection.v1",
             "headlineClaimIds":["invented-claim"],"sections":[]}
            """, compilation);

        assertThat(projection.modelSelectionAccepted()).isFalse();
        assertThat(projection.reason()).isEqualTo("UNKNOWN_FINAL_CLAIM_ID");
        assertThat(projection.markdown()).contains("返回值为 42").doesNotContain("invented-claim");
    }

    @Test
    void rejectedClaimIsNeverAddedToPublicationLedger() {
        AnalysisSummaryResult rejected = summary().withEvidence(Map.of(
            "insights", List.of(insight("claim-2", "不应发布")),
            "claimAdmissionDecisions", List.of(Map.of(
                "claimId", "claim-2", "admitted", false))));

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(rejected));

        assertThat(compilation.active()).isFalse();
        assertThat(compilation.claimContractObserved()).isTrue();
    }

    @Test
    void legacySummaryWithoutClaimContractIsDistinguishedFromRejectedClaims() {
        AnalysisSummaryResult legacy = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset-a", "chunkIndex", 1), Map.of(),
            "legacy narrative", "MODEL_SUMMARY", Map.of());

        GovernedFinalClaimContract.Compilation compilation = contract.compile(List.of(legacy));

        assertThat(compilation.active()).isFalse();
        assertThat(compilation.claimContractObserved()).isFalse();
    }

    private AnalysisSummaryResult summary() {
        return AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset-a", "chunkIndex", 1), Map.of(),
            "返回值为 42", "MODEL_SUMMARY", Map.of(
                "insights", List.of(insight("claim-1", "返回值为 42")),
                "claimAdmissionDecisions", List.of(Map.of(
                    "claimId", "claim-1", "admitted", true))));
    }

    private Map<String, Object> insight(String id, String text) {
        return Map.of(
            "claimId", id,
            "claim", text,
            "claimClass", "OBSERVED_RETURNED_FACT",
            "confidence", "HIGH",
            "recordRefs", List.of("dataset.records[1]"),
            "supportingValues", List.of("42"),
            "caveats", List.of());
    }
}
