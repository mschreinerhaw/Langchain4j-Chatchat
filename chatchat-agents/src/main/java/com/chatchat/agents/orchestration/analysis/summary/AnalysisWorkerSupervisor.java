package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisWorkerSupervision;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisDecisionOperatingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Driver-side admission of Worker products; execution completion alone is never sufficient. */
final class AnalysisWorkerSupervisor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorkerSupervisor.class);

    DataAnalysisWorkerSupervision.WorkerReport inspect(
        String datasetReference,
        int returnedRecordCount,
        AnalysisDispatchCoordinator.Outcome outcome,
        Predicate<AnalysisSummaryResult> traceable
    ) {
        if (outcome == null || !outcome.success() || outcome.summary() == null) {
            return report(datasetReference, returnedRecordCount, outcome,
                DataAnalysisWorkerSupervision.ProductStatus.EXECUTION_FAILED,
                0, 0, List.of(outcome == null ? "MISSING_WORKER_RESULT"
                    : text(outcome.error(), "WORKER_EXECUTION_FAILED")), Map.of());
        }
        AnalysisDatasetSummary summary = outcome.summary();
        int acceptedChunks = 0;
        int degradedChunks = 0;
        List<String> reasons = new ArrayList<>();
        for (AnalysisDatasetSummary.ChunkResult chunk : summary.chunks()) {
            AnalysisSummaryResult result = chunk.summary();
            if (validAnalysisProduct(result, traceable)) {
                acceptedChunks++;
            } else if (degradedAnalysisProduct(result, traceable)) {
                degradedChunks++;
                reasons.add("ANALYSIS_PROTOCOL_DEGRADED");
            } else {
                reasons.add(rejectionReason(result));
            }
        }
        int rejectedChunks = Math.max(0,
            summary.chunks().size() - acceptedChunks - degradedChunks);
        boolean complete = !summary.chunks().isEmpty() && rejectedChunks == 0
            && !"FALLBACK".equalsIgnoreCase(summary.outcome())
            && summary.datasetSummary().content() != null
            && !summary.datasetSummary().content().isBlank();
        DataAnalysisWorkerSupervision.ProductStatus status = complete && degradedChunks == 0
            ? DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED
            : complete || acceptedChunks > 0 || degradedChunks > 0
                ? DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_DEGRADED
                : DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_NOT_PRODUCED;
        if (!complete && reasons.isEmpty()) reasons.add("DATASET_ANALYSIS_CONTRACT_NOT_SATISFIED");
        DataAnalysisWorkerSupervision.WorkerReport report = report(
            datasetReference, returnedRecordCount, outcome, status,
            acceptedChunks, rejectedChunks, reasons, Map.of(
                "datasetOutcome", summary.outcome(),
                "datasetSummaryOutcome", summary.datasetSummary().outcome(),
                "chunkCount", summary.chunks().size(),
                "degradedChunkCount", degradedChunks,
                "resultId", summary.resultId()));
        log.info("analysisWorkerProductSupervision dataset={} worker={} executionStatus={} "
                + "productStatus={} acceptedForSynthesis={} acceptedChunks={} degradedChunks={} "
                + "rejectedChunks={} reasons={}",
            report.datasetReference(), report.workerId(), report.executionStatus(),
            report.productStatus(), report.acceptedForSynthesis(), acceptedChunks,
            degradedChunks, rejectedChunks, reasons);
        return report;
    }

    private boolean validAnalysisProduct(
        AnalysisSummaryResult result,
        Predicate<AnalysisSummaryResult> traceable
    ) {
        if (result == null || !"MODEL_SUMMARY".equals(result.outcome())
            || result.content() == null || result.content().isBlank()
            || !Boolean.TRUE.equals(result.evidence().get("structured"))
            || !completeWorkerAnalysisReport(result)
            || traceable == null || !traceable.test(result)) {
            return false;
        }
        if (number(result.evidence().get("rejectedFactCount")) > 0
            || number(result.evidence().get("invalidInsightCount")) > 0) {
            return false;
        }
        return nonEmpty(result.evidence().get("insights"))
            || nonEmpty(result.evidence().get("observedFactClaims"))
            || nonEmpty(result.evidence().get("analysisItems"))
            || nonEmpty(result.evidence().get("unsupportedQuestions"))
            || nonEmpty(result.evidence().get("missingEvidence"));
    }

    private String rejectionReason(AnalysisSummaryResult result) {
        if (result == null) return "MISSING_ANALYSIS_PRODUCT";
        if (result.outcome().contains("FALLBACK") || result.outcome().contains("DIRECT")) {
            return "RAW_RECORD_PRODUCT_IS_NOT_ANALYSIS";
        }
        if (!"MODEL_SUMMARY".equals(result.outcome())) return "MODEL_ANALYSIS_NOT_PRODUCED";
        if (!Boolean.TRUE.equals(result.evidence().get("structured"))) {
            return "ANALYSIS_PROTOCOL_NOT_SATISFIED";
        }
        if (!completeWorkerAnalysisReport(result)) {
            return "WORKER_ANALYSIS_REPORT_INCOMPLETE";
        }
        return "ANALYSIS_HAS_NO_ADMISSIBLE_FINDING_OR_GAP";
    }

    private boolean degradedAnalysisProduct(
        AnalysisSummaryResult result,
        Predicate<AnalysisSummaryResult> traceable
    ) {
        if (result == null || !"MODEL_SUMMARY".equals(result.outcome())
            || result.content() == null || result.content().isBlank()
            || traceable == null || !traceable.test(result)) {
            return false;
        }
        // Protocol completeness is a quality grade, not proof that analysis does not exist.
        // A readable model-authored report bound to immutable returned-record evidence remains
        // usable as a degraded Worker product; Reducer/Driver can preserve its limitations.
        return AnalysisOutputAdmissionPolicy.admitWorkerNarrative(result.content()).admitted();
    }

    private boolean completeWorkerAnalysisReport(AnalysisSummaryResult result) {
        if (result == null || result.evidence() == null) return false;
        return DataAnalysisDecisionOperatingModel.SCHEMA_VERSION.equals(
                result.evidence().get("analysisDecisionOperatingModelVersion"))
            && DataAnalysisDecisionOperatingModel.ParticipantRole.WORKER.name().equals(
                result.evidence().get("analysisParticipantRole"))
            && DataAnalysisDecisionOperatingModel.WORKER_REPORT_SCHEMA_VERSION.equals(
                result.evidence().get("workerAnalysisReportSchemaVersion"))
            && Boolean.TRUE.equals(result.evidence().get("workerDemandAnalysisComplete"))
            && Boolean.TRUE.equals(
                result.evidence().get("workerMetricAssociationAssessmentDeclared"));
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

    private boolean nonEmpty(Object value) {
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return value != null && !String.valueOf(value).isBlank();
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
