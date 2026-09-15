package com.chatchat.agents.orchestration.analysis.nodes.analysis;

import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.common.runtime.summary.analysis.governance.DataAnalysisWorkerSupervision;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driver-side accounting for Worker preprocessing results.
 *
 * <p>A Worker is a partitioning/preprocessing stage. This class deliberately does not judge
 * whether its narrative, claims, citations, or analysis protocol are good enough. Those are
 * inputs for the Driver model to interpret, not runtime admission criteria.</p>
 */
public final class WorkerPreprocessingCollector {

    private static final Logger log = LoggerFactory.getLogger(WorkerPreprocessingCollector.class);

    public DataAnalysisWorkerSupervision.WorkerReport inspect(
        String datasetReference,
        int returnedRecordCount,
        AnalysisDispatchCoordinator.Outcome outcome,
        Predicate<AnalysisSummaryResult> ignoredTraceabilityCheck
    ) {
        if (outcome == null || outcome.summary() == null) {
            return report(datasetReference, returnedRecordCount, outcome,
                DataAnalysisWorkerSupervision.ProductStatus.EXECUTION_FAILED,
                0, 0, List.of(outcome == null ? "MISSING_WORKER_RESULT"
                    : text(outcome.error(), "WORKER_EXECUTION_FAILED")), Map.of());
        }

        AnalysisDatasetSummary summary = outcome.summary();
        boolean datasetContentAvailable = hasText(summary.datasetSummary().content())
            || hasText(summary.content());
        int availableChunks = (int) summary.chunks().stream()
            .map(AnalysisDatasetSummary.ChunkResult::summary)
            .filter(java.util.Objects::nonNull)
            .filter(result -> hasText(result.content()))
            .count();
        boolean collectable = datasetContentAvailable || availableChunks > 0;
        DataAnalysisWorkerSupervision.ProductStatus status = collectable
            ? DataAnalysisWorkerSupervision.ProductStatus.PREPROCESSING_AVAILABLE
            : DataAnalysisWorkerSupervision.ProductStatus.PREPROCESSING_EMPTY;
        List<String> reasons = collectable ? List.of() : List.of("PREPROCESSING_OUTPUT_EMPTY");
        DataAnalysisWorkerSupervision.WorkerReport report = report(
            datasetReference, returnedRecordCount, outcome, status,
            availableChunks, Math.max(0, summary.chunks().size() - availableChunks), reasons,
            Map.of(
                "datasetOutcome", summary.outcome(),
                "datasetSummaryOutcome", summary.datasetSummary().outcome(),
                "chunkCount", summary.chunks().size(),
                "availableChunkCount", availableChunks,
                "resultId", summary.resultId()));
        log.info("analysisWorkerPreprocessing dataset={} worker={} executionStatus={} "
                + "collectable={} availableChunks={} emptyChunks={} reasons={}",
            report.datasetReference(), report.workerId(), report.executionStatus(),
            report.acceptedForSynthesis(), availableChunks, report.rejectedChunkCount(), reasons);
        return report;
    }

    private DataAnalysisWorkerSupervision.WorkerReport report(
        String datasetReference,
        int returnedRecordCount,
        AnalysisDispatchCoordinator.Outcome outcome,
        DataAnalysisWorkerSupervision.ProductStatus status,
        int acceptedChunks,
        int rejectedChunks,
        List<String> reasons,
        Map<String, Object> evidence
    ) {
        return new DataAnalysisWorkerSupervision.WorkerReport(
            datasetReference,
            datasetReference,
            outcome == null ? "unknown-worker" : outcome.workerId(),
            outcome == null ? "MISSING" : outcome.status(),
            status, returnedRecordCount, acceptedChunks, rejectedChunks,
            outcome == null || outcome.summary() == null ? 0 : outcome.summary().totalRetryCount(),
            outcome == null ? 0L : outcome.durationMs(), reasons, evidence);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
