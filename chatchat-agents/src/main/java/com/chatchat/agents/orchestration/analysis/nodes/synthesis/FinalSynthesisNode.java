package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.model.AnalysisExecutionOutcome;
import com.chatchat.agents.orchestration.analysis.model.AnalysisReportContract;
import com.chatchat.agents.orchestration.analysis.report.VerifiedReportDataCatalog;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisExecutionOutcomeRecorder;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisSummaryGovernanceCoordinator;
import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;
import com.chatchat.agents.orchestration.analysis.context.SynthesisContextBudget;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates dataset, cross-dataset and final governed synthesis without domain knowledge.
 */
public final class FinalSynthesisNode {

    private static final Logger log = LoggerFactory.getLogger(FinalSynthesisNode.class);

    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;
    private final AnalysisSummaryGovernanceCoordinator governanceCoordinator;
    private final AnswerCandidateCollector answerCandidateCollector;
    private final AnalysisExecutionOutcomeRecorder outcomeRecorder = new AnalysisExecutionOutcomeRecorder();
    public FinalSynthesisNode(
        AgentRunResultAdapter resultAdapter,
        String runIdAttribute,
        AnalysisSummaryGovernanceCoordinator governanceCoordinator,
        AnswerCandidateCollector answerCandidateCollector
    ) {
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
        this.governanceCoordinator = governanceCoordinator;
        this.answerCandidateCollector = answerCandidateCollector;
    }
    public AnalysisSummaryResult finalizeSummary(FinalSynthesisRequest request) {
        return governanceCoordinator.finalizeSummary(
            new AnalysisSummaryGovernanceCoordinator.FinalSummaryRequest(
                request.stage(), request.content(), request.outcome(),
                request.returnedRecordCount(), request.processedRecordCount(),
                request.coverageComplete(), request.evidenceTraceComplete(),
                request.sourceContentComplete(), request.iterationCount(),
                request.rawReplayChunkCount(), request.summaryResults(),
                request.synthesisInputs(), request.runtimeAttributes(), request.metadata()));
    }
    public FinalSynthesisResult synthesizeFinal(FinalModelSynthesisRequest request) {
        try {
            return new ReportPublicationGraph().execute(request, this::publishModelReport);
        } catch (RuntimeException ex) {
            request.metadata().put("analysisGraphStatus", ex instanceof java.util.concurrent.CancellationException
                ? "CANCELLED" : "FAILED");
            request.metadata().put("interpretationPlanFinalResultProduced", false);
            request.metadata().remove("analyticalReport");
            request.metadata().remove("claimAcceptance");
            request.metadata().remove("claimAcceptanceGraphNodes");
            throw ex;
        }
    }
    private FinalSynthesisResult publishModelReport(FinalModelSynthesisRequest request) {
        if (String.valueOf(request.metadata().getOrDefault("modelNativeReportDraft", "")).isBlank()) {
            // A request without dataset evidence still receives one model-authored summary.
            if (request.model() == null) throw new IllegalStateException("Analysis model unavailable");
            if (new ContextTokenEstimator().estimate(request.prompt()).tokens() > SynthesisContextBudget.fromRuntime(request.metadata()).inputTokens())
                throw new IllegalStateException("Report context exceeds the model input budget");
            String draft = request.model().chat(request.prompt());
            request.metadata().put("modelNativeReportDraft", draft == null ? "" : draft);
            request.metadata().put("modelNativeHarnessActive", true);
        }
        request.metadata().put("modelNativeHarnessActive", true);
        return publishHarnessReport(request);
    }
    private FinalSynthesisResult publishHarnessReport(FinalModelSynthesisRequest request) {
        String answer = String.valueOf(request.metadata().getOrDefault("modelNativeReportDraft", ""));
        if (answer.isBlank()) throw new IllegalStateException("Model-native report is empty");
        var catalog = VerifiedReportDataCatalog.fromRuntime(request.metadata());
        request.metadata().remove("reportBlocks");
        request.metadata().remove("visualizationPlanning");
        boolean hasProposals = !com.chatchat.agents.orchestration.analysis.report.ReportBlockMarkdownProtocol
            .protectProposals(answer).payloads().isEmpty();
        if (hasProposals) {
            var registry = com.chatchat.agents.orchestration.analysis.report.VisualizationCapabilityRegistry.active(request.runtimeAttributes());
            var plan = new com.chatchat.agents.orchestration.analysis.report.VisualizationPlanningNode(registry).execute(answer, catalog);
            answer = plan.markdown();
            request.metadata().put("analysisVisualizationAudit", plan.checks());
            request.metadata().put("reportBlocks", Map.of("schemaVersion", "report_blocks.v1", "reportId", request.runId(), "blocks", plan.blocks()));
            var planning = Map.of("stage", "VISUALIZATION_PLANNING", "modelCalls", 0,
                "verifiedBlockCount", plan.visualizationCount(), "status", plan.visualizationCount() > 0 ? "COMPLETED" : "TEXT_ONLY");
            request.metadata().put("visualizationPlanning", planning);
            resultAdapter.recordRuntimeObservation(request.runtimeAttributes(), runIdAttribute,
                "Model-selected report artifacts bound to run-scoped evidence.", "visualization_planning",
                Map.of("type", "visualization_planning", "eventKind", "VISUALIZATION_PLANNING", "stage", "VISUALIZATION_PLANNING", "planning", planning, "checks", plan.checks()));
        }
        if (answer.isBlank()) throw new IllegalStateException("Report contains no publishable content");
        request.metadata().put("analysisReportGenerationMode", "MODEL_NATIVE_HARNESS");
        request.metadata().put("analysisSemanticReviewPolicy", "NONE_USER_JUDGES");
        request.metadata().put("analysisReportBodyPreserved", true);
        request.metadata().put("analysisReportContract", AnalysisReportContract.modelReport(answer, 0, 0, 1).toMap());
        request.metadata().put("analysisReportContractSchemaVersion", AnalysisReportContract.SCHEMA_VERSION);
        request.metadata().put("analysisSynthesisBlocked", false);
        request.metadata().put("interpretationPlanSummaryGenerated", true);
        request.metadata().put("interpretationPlanFinalResultProduced", true);
        request.metadata().put("interpretationPlanSummaryStage", request.stage());
        recordCompletedOutcome(request);
        var governed = finalizeSummary(request.governance(answer, "MODEL_NATIVE_REPORT"));
        answerCandidateCollector.register(request.metadata(), AnswerCandidateCollector.FINAL_SYNTHESIS, answer);
        resultAdapter.recordRuntimeObservation(request.runtimeAttributes(), runIdAttribute,
            "Model-authored report published without a semantic quality gate or another summary model call.", "interpretation_plan_summary",
            Map.of("type", "final_summary", "workflow", "model_native_harness", "stage", request.stage(),
                "modelCalls", 0, "analysisSummaryResult", governed.toMap(), "reportQualityAuthority", "USER"));
        return new FinalSynthesisResult(answer, governed, true);
    }
    private void recordCompletedOutcome(FinalModelSynthesisRequest request) {
        boolean complete = request.coverageComplete() && request.evidenceTraceComplete()
            && request.sourceContentComplete();
        AnalysisExecutionOutcome outcome = new AnalysisExecutionOutcome(
            AnalysisExecutionOutcome.SCHEMA_VERSION,
            complete ? AnalysisExecutionOutcome.ExecutionStatus.COMPLETED
                : AnalysisExecutionOutcome.ExecutionStatus.PARTIALLY_COMPLETED,
            AnalysisExecutionOutcome.FailureCategory.NONE,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            outcomeRecorder.unresolvedGaps(request.metadata(), request.synthesisInputs()),
            AnalysisExecutionOutcome.RetryDirective.none(),
            AnalysisExecutionOutcome.Publishability.PUBLISHABLE_ANALYSIS,
            complete ? "ANALYSIS_COMPLETED" : "ANALYSIS_COMPLETED_WITH_LIMITATIONS");
        request.metadata().put("analysisExecutionOutcome", outcome.toMap());
        request.metadata().put("analysisExecutionOutcomeSchemaVersion",
            AnalysisExecutionOutcome.SCHEMA_VERSION);
        request.metadata().put("analysisExecutionStatus", outcome.status().name());
        request.metadata().put("analysisFailureCategory", outcome.failureCategory().name());
        request.metadata().put("analysisReportChannel", "analysis_report");
        request.metadata().putIfAbsent("supportingDatasetChannel", "supporting_dataset");
    }
    public String presentGovernedAnalysis(String answer, PresentationRequest request) { return answer; }
    public record FinalSynthesisRequest(
        String stage,
        String content,
        String outcome,
        int returnedRecordCount,
        int processedRecordCount,
        boolean coverageComplete,
        boolean evidenceTraceComplete,
        boolean sourceContentComplete,
        int iterationCount,
        int rawReplayChunkCount,
        List<AnalysisSummaryResult> summaryResults,
        List<AnalysisSummaryResult> synthesisInputs,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        public FinalSynthesisRequest {
            summaryResults = summaryResults == null ? List.of() : List.copyOf(summaryResults);
            synthesisInputs = synthesisInputs == null ? List.of() : List.copyOf(synthesisInputs);
        }
    }
    public record FinalModelSynthesisRequest(
        ChatModel model,
        String prompt,
        String stage,
        String runId,
        int stepCount,
        int attemptCount,
        int storedObservationCount,
        int returnedRecordCount,
        int processedRecordCount,
        boolean coverageComplete,
        boolean evidenceTraceComplete,
        boolean sourceContentComplete,
        int iterationCount,
        int rawReplayChunkCount,
        List<AnalysisSummaryResult> summaryResults,
        List<AnalysisSummaryResult> synthesisInputs,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        public FinalModelSynthesisRequest {
            prompt = prompt == null ? "" : prompt;
            stage = stage == null ? "final_synthesis" : stage;
            runId = runId == null ? "" : runId;
            summaryResults = summaryResults == null ? List.of() : List.copyOf(summaryResults);
            synthesisInputs = synthesisInputs == null ? List.of() : List.copyOf(synthesisInputs);
            runtimeAttributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
            metadata = metadata == null ? new LinkedHashMap<>() : metadata;
        }

        private FinalSynthesisRequest governance(String content, String outcome) {
            return new FinalSynthesisRequest(stage, content, outcome, returnedRecordCount,
                processedRecordCount, coverageComplete, evidenceTraceComplete,
                sourceContentComplete, iterationCount, rawReplayChunkCount,
                summaryResults, synthesisInputs, runtimeAttributes, metadata);
        }
    }
    public record FinalSynthesisResult(
        String content,
        AnalysisSummaryResult governedResult,
        boolean generated
    ) {}

    public record PresentationRequest(
        String appendix,
        List<List<String>> recordValueGroups,
        int returnedRecordCount,
        boolean iterative,
        boolean coverageComplete,
        boolean sourceContentComplete,
        boolean evidenceTraceComplete,
        List<AnalysisSummaryResult> summaryResults,
        List<AnalysisSummaryResult> synthesisInputs,
        Map<String, Object> metadata
    ) {
        public PresentationRequest {
            appendix = appendix == null ? "" : appendix;
            recordValueGroups = recordValueGroups == null ? List.of() : List.copyOf(recordValueGroups);
            summaryResults = summaryResults == null ? List.of() : List.copyOf(summaryResults);
            synthesisInputs = synthesisInputs == null ? List.of() : List.copyOf(synthesisInputs);
            metadata = metadata == null ? new LinkedHashMap<>() : metadata;
        }
    }
}
