package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.model.AnalysisExecutionOutcome;
import com.chatchat.agents.orchestration.analysis.model.AnalysisReportContract;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.protocol.ModelProtocolJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records non-publishable analysis execution state without making it a Driver concern. */
public final class AnalysisExecutionOutcomeRecorder {

    public AnalysisExecutionOutcome recordBlocked(
        Map<String, Object> metadata,
        int returnedRecordCount,
        List<AnalysisSummaryResult> summaryResults,
        List<AnalysisSummaryResult> synthesisInputs,
        String reason,
        boolean governanceReached
    ) {
        List<Map<String, Object>> gaps = unresolvedGaps(metadata, synthesisInputs);
        boolean dataAvailable = returnedRecordCount > 0;
        boolean workersAccepted = number(metadata.get("analysisAcceptedWorkerCount")) > 0
            || !summaryResults.isEmpty();
        boolean reducersAccepted = !synthesisInputs.isEmpty();
        AnalysisExecutionOutcome.ExecutionStatus status;
        AnalysisExecutionOutcome.FailureCategory category;
        AnalysisExecutionOutcome.RetryDirective retry;
        boolean driverReviewFailure = reason != null && reason.startsWith("DRIVER_REVIEW");
        boolean driverDecisionFailure = reason != null && reason.startsWith("DRIVER_DECISION");
        boolean driverChallengeFailure = "DRIVER_CHALLENGE_REQUIRES_REPAIR".equals(reason);
        boolean workerChallenge = maps(metadata.get("analysisDriverChallenges"))
            .stream().anyMatch(challenge ->
                "WORKER_REPORT".equals(text(challenge.get("targetLayer"))));
        if (!dataAvailable) {
            status = AnalysisExecutionOutcome.ExecutionStatus.EXECUTION_FAILED;
            category = AnalysisExecutionOutcome.FailureCategory.DATA_FAILURE;
            retry = AnalysisExecutionOutcome.RetryDirective.none();
        } else if (driverReviewFailure || driverDecisionFailure || driverChallengeFailure) {
            status = AnalysisExecutionOutcome.ExecutionStatus.NEEDS_REANALYSIS;
            category = AnalysisExecutionOutcome.FailureCategory.GOVERNANCE_REJECTION;
            String resumeFrom = driverReviewFailure ? "DRIVER_REVIEW"
                : driverDecisionFailure ? "DRIVER_DECISION"
                : workerChallenge ? "WORKER_ANALYSIS" : "REDUCER_REVIEW";
            retry = new AnalysisExecutionOutcome.RetryDirective(
                driverReviewFailure ? "RETRY_DRIVER_REVIEW"
                    : driverDecisionFailure ? "RETRY_DRIVER_DECISION" : "RETRY_ANALYSIS",
                resumeFrom, true, false, 1);
        } else {
            status = AnalysisExecutionOutcome.ExecutionStatus.NEEDS_REANALYSIS;
            category = governanceReached
                ? AnalysisExecutionOutcome.FailureCategory.GOVERNANCE_REJECTION
                : AnalysisExecutionOutcome.FailureCategory.ANALYSIS_FAILURE;
            String resumeFrom = driverReviewFailure ? "DRIVER_REVIEW"
                : workerChallenge ? "WORKER_ANALYSIS"
                : workersAccepted ? "REDUCER_REVIEW" : "WORKER_ANALYSIS";
            retry = new AnalysisExecutionOutcome.RetryDirective(
                driverReviewFailure ? "RETRY_DRIVER_REVIEW" : "RETRY_ANALYSIS", resumeFrom,
                true, false, 1);
        }
        AnalysisExecutionOutcome executionOutcome = new AnalysisExecutionOutcome(
            AnalysisExecutionOutcome.SCHEMA_VERSION, status, category,
            dataAvailable ? AnalysisExecutionOutcome.PhaseStatus.COMPLETED
                : AnalysisExecutionOutcome.PhaseStatus.FAILED,
            workersAccepted ? AnalysisExecutionOutcome.PhaseStatus.COMPLETED
                : AnalysisExecutionOutcome.PhaseStatus.REJECTED,
            reducersAccepted ? AnalysisExecutionOutcome.PhaseStatus.COMPLETED
                : AnalysisExecutionOutcome.PhaseStatus.BLOCKED,
            AnalysisExecutionOutcome.PhaseStatus.BLOCKED,
            governanceReached ? AnalysisExecutionOutcome.PhaseStatus.REJECTED
                : AnalysisExecutionOutcome.PhaseStatus.NOT_STARTED,
            gaps, retry, AnalysisExecutionOutcome.Publishability.GOVERNED_FAILURE_REPORT_ONLY,
            reason);
        metadata.put("analysisExecutionOutcome", executionOutcome.toMap());
        metadata.put("analysisExecutionOutcomeSchemaVersion", AnalysisExecutionOutcome.SCHEMA_VERSION);
        metadata.put("analysisExecutionStatus", executionOutcome.status().name());
        metadata.put("analysisFailureCategory", executionOutcome.failureCategory().name());
        metadata.put("analysisRetryDirective", executionOutcome.retryDirective().toMap());
        metadata.put("analysisReuseExistingDataset", retry.reuseExistingDataset());
        metadata.put("analysisDataRequeryAllowed", retry.dataAcquisitionAllowed());
        metadata.put("returnedDataAnalysisRequired", dataAvailable);
        metadata.put("rawAnalysisOutputWithheld", dataAvailable);
        metadata.put("supportingDatasetChannel", "supporting_dataset");
        metadata.put("supportingDatasetPrimaryDisplayAllowed", false);
        metadata.put("supportingDatasetDefaultCollapsed", true);
        AnalysisReportContract reportContract = AnalysisReportContract.failureReport(
            executionOutcome.failureReport());
        metadata.put("analysisReportContract", reportContract.toMap());
        metadata.put("analysisReportContractSchemaVersion", AnalysisReportContract.SCHEMA_VERSION);
        metadata.put("finalPayloadType", reportContract.reportType().name());
        return executionOutcome;
    }

    public List<Map<String, Object>> unresolvedGaps(
        Map<String, Object> metadata,
        List<AnalysisSummaryResult> reports
    ) {
        LinkedHashMap<String, Map<String, Object>> gaps = new LinkedHashMap<>();
        for (String key : List.of("analysisGapRequests", "semanticClaimGapRequests", "gapRequests")) {
            for (Map<String, Object> gap : maps(metadata.get(key))) {
                gaps.putIfAbsent(gapKey(gap), gap);
            }
        }
        for (AnalysisSummaryResult report : reports) {
            if (report == null || report.evidence() == null) continue;
            for (String key : List.of("semanticGapRequests", "analysisDepthGapRequests")) {
                for (Map<String, Object> gap : maps(report.evidence().get(key))) {
                    gaps.putIfAbsent(gapKey(gap), gap);
                }
            }
        }
        return List.copyOf(gaps.values());
    }

    private String gapKey(Map<String, Object> gap) {
        Object id = gap.get("requestId");
        if (id == null) id = gap.get("questionId");
        if (id == null) id = gap.get("gapId");
        return id == null || String.valueOf(id).isBlank()
            ? ModelProtocolJson.sha256Hex(gap) : String.valueOf(id);
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> source) result.add(map(source));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> map(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
