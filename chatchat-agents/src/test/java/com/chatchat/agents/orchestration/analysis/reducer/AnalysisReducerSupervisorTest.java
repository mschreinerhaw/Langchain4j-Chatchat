package com.chatchat.agents.orchestration.analysis.reducer;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisDecisionOperatingModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisReducerSupervisorTest {

    private final GovernanceIsolationScope scope =
        GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation");

    @Test
    void admitsTraceableReducerReportAndKeepsEvidenceGapAdvisory() {
        AnalysisSummaryResult worker = AnalysisSummaryResult.chunk(
            scope, Map.of("datasetReference", "dataset", "chunkIndex", 1), Map.of(),
            "worker analysis", "MODEL_SUMMARY", Map.of("evidenceId", "evidence-1"));
        AnalysisSummaryResult reducer = AnalysisSummaryResult.intermediateSummary(
            scope, "DATASET_SYNTHESIS", "reducer-1", "reduced analysis",
            "MODEL_DATASET_REDUCE", Map.of("datasetReference", "dataset"), Map.of(),
            Map.of("complete", true), List.of(worker), Map.of(
                "analysisDecisionOperatingModelVersion",
                    DataAnalysisDecisionOperatingModel.SCHEMA_VERSION,
                "analysisParticipantRole", "REDUCER",
                "managementReviewInput", true,
                "observedFactClaims", List.of(Map.of(
                    "claimId", "observed-fact:asset", "claim", "Observed account value")),
                "missingEvidence", List.of("comparison baseline missing")));

        AnalysisReducerSupervisor.Review review =
            new AnalysisReducerSupervisor().inspect(List.of(reducer));

        assertThat(review.admittedInputs()).singleElement().satisfies(result -> {
            assertThat(result.evidence()).containsKeys(
                "analysisReportAdmission", "analysisEvidenceLineage", "analysisRepairRequests");
            assertThat(result.evidence().get("analysisReportAdmission").toString())
                .contains("ADMITTED", "REDUCER_REPORT", "observed-fact:asset");
        });
        assertThat(review.repairRequests()).isEmpty();
        assertThat(review.admittedInputs().get(0).evidence().get("missingEvidence"))
            .isEqualTo(List.of("comparison baseline missing"));
        assertThat(review.rejectedCount()).isZero();
    }

    @Test
    void flagsReducerReportWithoutLayerContractButKeepsItReviewableByDriver() {
        AnalysisSummaryResult worker = AnalysisSummaryResult.chunk(
            scope, Map.of("datasetReference", "dataset", "chunkIndex", 1), Map.of(),
            "worker analysis", "MODEL_SUMMARY", Map.of("evidenceId", "evidence-1"));
        AnalysisSummaryResult invalid = AnalysisSummaryResult.intermediateSummary(
            scope, "DATASET_SYNTHESIS", "invalid-reducer", "unmarked reducer output",
            "MODEL_DATASET_REDUCE", Map.of(), Map.of(), Map.of(), List.of(worker), Map.of());

        AnalysisReducerSupervisor.Review review =
            new AnalysisReducerSupervisor().inspect(List.of(invalid));

        assertThat(review.admittedInputs()).singleElement().satisfies(result -> {
            assertThat(result.content()).isEqualTo("unmarked reducer output");
            assertThat(result.evidence())
                .containsEntry("analysisGovernanceAdvisoryOnly", true)
                .containsEntry("analysisHumanReviewRequired", true);
        });
        assertThat(review.rejectedCount()).isEqualTo(1);
        assertThat(review.admissionDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision).containsEntry("admitted", false)
                .containsEntry("state", "NEEDS_EVIDENCE");
            assertThat(decision.get("reasons").toString())
                .contains("OPERATING_MODEL_VERSION_MISSING", "REDUCER_ROLE_NOT_DECLARED");
        });
        assertThat(review.repairRequests()).singleElement().satisfies(repair ->
            assertThat(repair.toString()).contains("RERUN_REDUCER", "REDUCER_REPORT"));
    }
}
