package com.chatchat.agents.orchestration.analysis.summary;

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
            "facts", List.of(Map.of("claim", "observed", "exactValues", List.of("1")))));
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
    void distinguishesDegradedNarrativeFromRuntimeProtocolText() {
        AnalysisSummaryResult narrative = chunk(
            "MODEL_SUMMARY", "customer assets were analyzed", Map.of("evidenceId", "evidence-1"));
        AnalysisSummaryResult protocol = chunk(
            "MODEL_SUMMARY", "必需工具未执行：Tool call batch failed", Map.of("evidenceId", "evidence-2"));

        DataAnalysisWorkerSupervision.WorkerReport degraded = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(narrative, "SUCCESS")), ignored -> true);
        DataAnalysisWorkerSupervision.WorkerReport rejected = supervisor.inspect(
            "dataset-a", 1, outcome(dataset(protocol, "SUCCESS")), ignored -> true);

        assertThat(degraded.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_DEGRADED);
        assertThat(degraded.acceptedForSynthesis()).isTrue();
        assertThat(rejected.productStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_NOT_PRODUCED);
        assertThat(rejected.acceptedForSynthesis()).isFalse();
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
