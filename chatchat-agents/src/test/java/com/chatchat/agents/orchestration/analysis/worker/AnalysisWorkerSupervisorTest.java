package com.chatchat.agents.orchestration.analysis.worker;

import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisWorkerSupervision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisWorkerSupervisorTest {

    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
        "tenant", "user", "run", "request", "conversation");
    private final AnalysisWorkerSupervisor supervisor = new AnalysisWorkerSupervisor();

    @Test
    void acceptsOnlyStructuredTraceableWorkerAnalysis() {
        AnalysisSummaryResult chunk = chunk("MODEL_SUMMARY", "业务分析结论", Map.of(
            "structured", true,
            "evidenceId", "evidence-1",
            "analysisDecisionOperatingModelVersion", "data_analysis_decision_operating_model.v1",
            "analysisParticipantRole", "WORKER",
            "workerAnalysisReportSchemaVersion", "worker_analysis_report.v1",
            "workerDemandAnalysisComplete", true,
            "workerMetricAssociationAssessmentDeclared", true,
            "rejectedFactCount", 0,
            "rejectedInsightCount", 0,
            "insights", List.of(Map.of(
                "claimId", "claim-1", "claim", "observed",
                "recordRefs", List.of("dataset.records[1]"),
                "supportingValues", List.of("1")))));
        DataAnalysisWorkerSupervision.WorkerReport report = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(chunk, "SUCCESS")), ignored -> true);

        assertThat(report.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED);
        assertThat(report.acceptedForSynthesis()).isTrue();
    }

    @Test
    void acceptsFactOnlyWorkerReportAfterExactValueValidation() {
        AnalysisSummaryResult chunk = chunk("MODEL_SUMMARY", "Observed account metrics", Map.of(
            "structured", true,
            "evidenceId", "evidence-1",
            "analysisDecisionOperatingModelVersion", "data_analysis_decision_operating_model.v1",
            "analysisParticipantRole", "WORKER",
            "workerAnalysisReportSchemaVersion", "worker_analysis_report.v1",
            "workerDemandAnalysisComplete", true,
            "workerMetricAssociationAssessmentDeclared", true,
            "rejectedFactCount", 0,
            "rejectedInsightCount", 0,
            "observedFactClaims", List.of(Map.of(
                "claimId", "observed-fact:asset", "claim", "Total assets are 847174.25",
                "recordRefs", List.of("dataset-a.records[1]"),
                "supportingValues", List.of("847174.25")))));

        DataAnalysisWorkerSupervision.WorkerReport report = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(chunk, "SUCCESS")), ignored -> true);

        assertThat(report.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED);
        assertThat(report.acceptedForSynthesis()).isTrue();
    }

    @Test
    void acceptsEvidenceBoundDynamicAnalysisWithoutDuplicatedInsight() {
        AnalysisSummaryResult chunk = chunk("MODEL_SUMMARY", "Metric catalog was analyzed", Map.of(
            "structured", true,
            "evidenceId", "evidence-1",
            "analysisDecisionOperatingModelVersion", "data_analysis_decision_operating_model.v1",
            "analysisParticipantRole", "WORKER",
            "workerAnalysisReportSchemaVersion", "worker_analysis_report.v1",
            "workerDemandAnalysisComplete", true,
            "workerMetricAssociationAssessmentDeclared", true,
            "rejectedFactCount", 0,
            "invalidInsightCount", 0,
            "analysisItems", List.of(Map.of(
                "itemId", "buffer-health",
                "status", "SUPPORTED",
                "finding", "No allocation wait was returned",
                "basisRecordRefs", List.of("dataset-a.records[1]"),
                "supportingValues", List.of("0")))));

        DataAnalysisWorkerSupervision.WorkerReport report = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(chunk, "SUCCESS")), ignored -> true);

        assertThat(report.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED);
        assertThat(report.acceptedForSynthesis()).isTrue();
    }

    @Test
    void acceptsEvidenceBoundWorkerProductWithHumanReviewNotes() {
        AnalysisSummaryResult chunk = chunk("MODEL_SUMMARY", "业务分析结论", Map.of(
            "structured", true,
            "evidenceId", "evidence-1",
            "analysisDecisionOperatingModelVersion", "data_analysis_decision_operating_model.v1",
            "analysisParticipantRole", "WORKER",
            "workerAnalysisReportSchemaVersion", "worker_analysis_report.v1",
            "workerDemandAnalysisComplete", true,
            "workerMetricAssociationAssessmentDeclared", true,
            "invalidInsightCount", 0,
            "insights", List.of(Map.of(
                "claimId", "claim-1", "claim", "observed",
                "recordRefs", List.of("dataset.records[1]"),
                "supportingValues", List.of("1")))));

        DataAnalysisWorkerSupervision.WorkerReport report = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(chunk, "SUCCESS")), ignored -> true);

        assertThat(report.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED);
        assertThat(report.acceptedForSynthesis()).isTrue();
    }

    @Test
    void executionFallbackIsTerminalButNotAcceptedAsAnalysis() {
        AnalysisSummaryResult chunk = chunk(
            "STRUCTURED_RECORD_FALLBACK", "[{\"value\":1}]", Map.of(
                "structured", false, "evidenceId", "evidence-1"));
        DataAnalysisWorkerSupervision.WorkerReport report = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(chunk, "FALLBACK")), ignored -> true);

        assertThat(report.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_NOT_PRODUCED);
        assertThat(report.acceptedForSynthesis()).isFalse();
        assertThat(report.reasons()).contains("RAW_RECORD_PRODUCT_IS_NOT_ANALYSIS");
    }

    @Test
    void degradesTraceableUnstructuredNarrativeButRejectsRuntimeProtocolText() {
        AnalysisSummaryResult narrative = chunk(
            "MODEL_SUMMARY", "customer assets were analyzed", Map.of("evidenceId", "evidence-1"));
        AnalysisSummaryResult protocol = chunk(
            "MODEL_SUMMARY", "必需工具未执行：Tool call batch failed", Map.of("evidenceId", "evidence-2"));

        DataAnalysisWorkerSupervision.WorkerReport narrativeReport = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(narrative, "SUCCESS")), ignored -> true);
        DataAnalysisWorkerSupervision.WorkerReport rejected = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(protocol, "SUCCESS")), ignored -> true);

        assertThat(narrativeReport.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_DEGRADED);
        assertThat(narrativeReport.acceptedForSynthesis()).isTrue();
        assertThat(narrativeReport.reasons()).contains("ANALYSIS_PROTOCOL_DEGRADED");
        assertThat(rejected.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_NOT_PRODUCED);
        assertThat(rejected.acceptedForSynthesis()).isFalse();
    }

    @Test
    void degradesStructuredWorkerOutputWithoutDemandAndMetricAssessment() {
        AnalysisSummaryResult incomplete = chunk("MODEL_SUMMARY", "业务分析结论", Map.of(
            "structured", true,
            "evidenceId", "evidence-1",
            "analysisDecisionOperatingModelVersion", "data_analysis_decision_operating_model.v1",
            "analysisParticipantRole", "WORKER",
            "workerAnalysisReportSchemaVersion", "worker_analysis_report.v1",
            "workerDemandAnalysisComplete", false,
            "workerMetricAssociationAssessmentDeclared", false,
            "insights", List.of(Map.of("claimId", "claim-1"))));

        DataAnalysisWorkerSupervision.WorkerReport report = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(incomplete, "SUCCESS")), ignored -> true);

        assertThat(report.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_DEGRADED);
        assertThat(report.acceptedForSynthesis()).isTrue();
        assertThat(report.reasons()).contains("ANALYSIS_PROTOCOL_DEGRADED");
    }

    private AnalysisDispatchCoordinator.Outcome outcome(AnalysisDatasetSummary summary) {
        return new AnalysisDispatchCoordinator.Outcome(
            summary, summary.outcome(), "worker-1", 20L, "");
    }

    private AnalysisDatasetSummary dataset(AnalysisSummaryResult chunk, String outcome) {
        AnalysisDatasetSummary.ChunkResult chunkResult =
            new AnalysisDatasetSummary.ChunkResult(chunk, null, "sha", false, 1);
        AnalysisSummaryResult reduced = AnalysisSummaryResult.intermediateSummary(
            scope, "DATASET_SYNTHESIS", "dataset-summary#dataset-a", chunk.content(),
            "SINGLE_CHUNK_DATASET_REDUCE", Map.of("datasetReference", "dataset-a"),
            Map.of(), Map.of("complete", true), List.of(chunk), Map.of());
        return new AnalysisDatasetSummary(
            AnalysisDatasetSummary.SCHEMA_VERSION, "dataset-result", reduced.content(), outcome,
            scope, "dataset-a", 1, false, 100L, List.of(chunkResult), reduced,
            0, 0L, 0, 0, 0, 0, false, List.of(chunk.resultId()), Map.of());
    }

    private AnalysisSummaryResult chunk(String outcome, String content, Map<String, Object> evidence) {
        return AnalysisSummaryResult.chunk(scope, Map.of(
            "datasetReference", "dataset-a", "chunkIndex", 1, "chunkCount", 1,
            "recordFrom", 1, "recordTo", 1, "totalRecords", 1),
            Map.of(), content, outcome, evidence);
    }
}
