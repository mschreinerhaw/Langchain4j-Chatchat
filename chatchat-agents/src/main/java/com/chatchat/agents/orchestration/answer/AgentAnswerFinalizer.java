package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.orchestration.analysis.model.AnalysisReportContract;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.worker.AnalysisSummaryGovernanceBridge;


import com.chatchat.agents.evidence.normalization.EvidenceType;

import com.chatchat.agents.evidence.normalization.EvidenceChunk;

import com.chatchat.agents.evidence.answer.EvidenceAnswer;

import com.chatchat.agents.evidence.answer.AnswerAssemblyMode;

import com.chatchat.agents.protocol.AnswerContract;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.orchestration.evidence.EvidenceSufficiencyGate;
import com.chatchat.agents.orchestration.planning.validation.AgentRuntimeGuard;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;

import com.chatchat.agents.assessment.TaskResultAssessment;
import com.chatchat.agents.assessment.TaskResultAssessmentCompiler;
import com.chatchat.agents.assessment.RuntimeAnswerCandidate;
import com.chatchat.agents.assessment.McpResultEvidencePolicy;
import com.chatchat.agents.evidence.answer.AnswerAssemblyEngine;
import com.chatchat.agents.evidence.answer.AnswerAssemblyPolicy;
import com.chatchat.agents.evidence.answer.DeterministicAnswerCompiler;
import com.chatchat.agents.evidence.answer.EvidenceAnswerGroundingGuard;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import com.chatchat.agents.runtime.answer.AgentAnswerReviewer;
import com.chatchat.agents.runtime.observation.AgentRuntimeFactGroundingContract;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import com.chatchat.agents.runtime.answer.DraftArtifactRuntimePolicy;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.agents.orchestration.protocol.RuntimeProtocolDefaults;
import com.chatchat.agents.runtime.plan.diagnostic.DiagnosticRunStateMachine;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.interaction.UserFacingAnswerSanitizer;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds final agent answers and preserves the legacy execution result contract.
 */
@Slf4j
public class AgentAnswerFinalizer implements AgentAnswerFinalizationPort {

    private static final String DOCUMENT_EVIDENCE_CONTRACT = "document_evidence_v1";
    private static final String UNIFIED_EVIDENCE_CONTRACT = "evidence_v1";
    private static final String EVIDENCE_ANSWER_CONTRACT = "evidence_answer_v1";
    private static final String EXECUTION_CONTRACT = "evidence_execution_contract_v2_2";
    private static final String INSUFFICIENT_EVIDENCE_ANSWER = "根据当前文档证据不足，无法确认。";
    private static final int TOOL_DATA_INLINE_CELL_LIMIT = 240;
    private static final int TOOL_DATA_MARKDOWN_ROW_LIMIT = 20;
    private static final Pattern DOCUMENT_REF_PATTERN =
        Pattern.compile("doc://([^\\s\"',;\\]\\)}]+)#chunk=([^\\s\"',;\\]\\)}]+)");
    private static final Pattern WEB_REF_PATTERN =
        Pattern.compile("web://([^\\s\"',;\\]\\)}]+)#result=([^\\s\"',;\\]\\)}]+)");

    private final AgentAnswerReviewer answerReviewer;
    private final AgentRuntimeGuard runtimeGuard;
    private final long modelRequestTimeoutMs;
    private final EvidenceAnswerGroundingGuard groundingGuard = new EvidenceAnswerGroundingGuard();
    private final AnswerAssemblyEngine answerAssemblyEngine = new AnswerAssemblyEngine();
    private final TaskResultAssessmentCompiler taskResultAssessmentCompiler =
        new TaskResultAssessmentCompiler();
    private final McpResultEvidencePolicy mcpResultEvidencePolicy =
        new McpResultEvidencePolicy();
    private final AnswerDecisionEngine answerDecisionEngine = new AnswerDecisionEngine();
    private final DraftArtifactRuntimePolicy draftArtifactRuntimePolicy =
        new DraftArtifactRuntimePolicy();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentResultPresentationService resultPresentationService =
        new AgentResultPresentationService(objectMapper);
    private final DeterministicAnswerReportRenderer deterministicReportRenderer =
        new DeterministicAnswerReportRenderer(objectMapper);
    private final AnswerUserFacingPolicy userFacingPolicy = new AnswerUserFacingPolicy(objectMapper);
    private final AgentToolBudgetPort toolBudgetPolicy = new DefaultAgentToolBudgetPolicy();
    private DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> analysisSummaryGovernanceBridge =
        RuntimeProtocolDefaults.analysisSummary();
    private final AnswerEvidenceLedgerCompiler answerEvidenceLedgerCompiler =
        new AnswerEvidenceLedgerCompiler();
    private final AnswerEvidenceAuditService answerEvidenceAuditService =
        new AnswerEvidenceAuditService(answerEvidenceLedgerCompiler, userFacingPolicy);
    private final AnswerReviewCoordinator answerReviewCoordinator;
    private final AnswerQualityCoordinator answerQualityCoordinator;
    private final FinalSummaryWebSearchEnhancer finalSummaryWebSearchEnhancer;
    private final AgentRuntimeProperties agentRuntimeProperties;

    public void setAnalysisSummaryProtocol(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> analysisSummaryProtocol
    ) {
        if (analysisSummaryProtocol != null) {
            this.analysisSummaryGovernanceBridge = analysisSummaryProtocol;
        }
    }

    public AgentAnswerFinalizer(AgentAnswerReviewer answerReviewer, AgentRuntimeGuard runtimeGuard) {
        this(answerReviewer, runtimeGuard, null);
    }

    public AgentAnswerFinalizer(AgentAnswerReviewer answerReviewer,
                         AgentRuntimeGuard runtimeGuard,
                         ModelsConfig modelsConfig) {
        this(answerReviewer, runtimeGuard, modelsConfig, null, null, null, null);
    }

    public AgentAnswerFinalizer(AgentAnswerReviewer answerReviewer,
                         AgentRuntimeGuard runtimeGuard,
                         ModelsConfig modelsConfig,
                         ToolRegistry toolRegistry,
                         ToolRuntimeService toolRuntimeService,
                         ObjectMapper objectMapper,
                         AgentRuntimeProperties agentRuntimeProperties) {
        this.answerReviewer = answerReviewer;
        this.runtimeGuard = runtimeGuard;
        this.modelRequestTimeoutMs = modelRequestTimeoutMs(modelsConfig);
        this.answerReviewCoordinator = new AnswerReviewCoordinator(
            answerReviewer, this.answerEvidenceLedgerCompiler, this.modelRequestTimeoutMs);
        this.agentRuntimeProperties = agentRuntimeProperties == null
            ? new AgentRuntimeProperties() : agentRuntimeProperties;
        this.answerQualityCoordinator = new AnswerQualityCoordinator(
            objectMapper, this.agentRuntimeProperties, this.answerEvidenceLedgerCompiler,
            this.modelRequestTimeoutMs);
        this.finalSummaryWebSearchEnhancer = new FinalSummaryWebSearchEnhancer(
            toolRegistry, toolRuntimeService, objectMapper, this.agentRuntimeProperties);
    }

    public AgentOrchestrator.AgentExecutionResult finishExecution(String answer,
                                                           List<InteractionToolTrace> traces,
                                                           Map<String, Object> metadata,
                                                           List<String> observations) {
        return finishWithDecision(null, null, null, answer, null, null, null,
            traces, metadata, observations);
    }

    private AgentOrchestrator.AgentExecutionResult finishWithDecision(ChatModel activeChatModel,
                                                                      String query,
                                                                      String systemPrompt,
                                                                      String candidateAnswer,
                                                                      AgentAnswerReview review,
                                                                      AnswerDecisionEngine.EvidenceSignal evidenceSignal,
                                                                      AnswerQualityEvaluator.QualityReport qualityReport,
                                                                      List<InteractionToolTrace> traces,
                                                                      Map<String, Object> metadata,
                                                                      List<String> observations) {
        Map<String, Object> values = metadata == null ? new LinkedHashMap<>() : metadata;
        McpResultEvidencePolicy.Assessment mcpAssessment =
            recordMcpResultEvidencePolicy(values, traces);
        String policyCompliantCandidate = values.containsKey("analysisReportContract")
            ? enforceAnalysisReportContract(candidateAnswer, values)
            : enforceMcpResultAnalysisPolicy(candidateAnswer, mcpAssessment, values);
        AnswerQualityEvaluator.QualityReport policyCompliantQuality =
            Boolean.TRUE.equals(values.get("evidenceRefusalBlocked")) ? null : qualityReport;
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal == null
            ? evidenceSignal(policyCompliantCandidate, observations, values)
            : evidenceSignal;
        AnswerDecisionEngine.AnswerDecision decision = answerDecisionEngine.decide(
            new AnswerDecisionEngine.AnswerDecisionRequest(
                policyCompliantCandidate,
                review,
                signal,
                policyCompliantQuality,
                values
            )
        );
        values.putAll(decision.metadata());
        recordSelectedAnswerCandidate(values, decision);
        String selectedAnswer = decision.finalAnswer();
        selectedAnswer = answerQualityCoordinator.applyTargetedRepair(
            activeChatModel, query, systemPrompt, selectedAnswer, values, observations,
            this::sanitizeFinalMarkdown);
        String finalAnswer = sanitizeFinalMarkdown(selectedAnswer);
        if (values.containsKey("analysisReportContract")) {
            finalAnswer = enforceAnalysisReportContract(finalAnswer, values);
        }
        if (finalAnswer.isBlank()) {
            String metadataReport = deterministicReportRenderer.deterministicEnterpriseMetadataReport(traces);
            if (!metadataReport.isBlank()) {
                finalAnswer = metadataReport;
                values.put("deterministicFinalizationFallback", true);
                values.put("deterministicFinalizationSource", "enterprise_metadata_field_discovery.v1");
            }
        }
        if (finalAnswer.isBlank()) {
            String deterministicReport = deterministicReportRenderer.deterministicBatchReport(traces);
            if (!deterministicReport.isBlank()) {
                finalAnswer = deterministicReport;
                values.put("deterministicFinalizationFallback", true);
                values.put("deterministicFinalizationSource", "tool_call_batch_result");
            }
        }
        if (finalAnswer.isBlank() && mcpAssessment.resultAvailable()) {
            finalAnswer = analysisFailureReport(values,
                "NON_EMPTY_MCP_RESULT_WITHOUT_PUBLISHABLE_REPORT");
            values.put("evidenceRefusalBlocked", true);
            values.put("evidenceRefusalBlockedReason",
                "non_empty_mcp_result_with_empty_answer");
        }
        Map<String, Object> visualizationSpec;
        try {
            visualizationSpec = userFacingPolicy.toolResultVisualizationSpec(traces);
        } catch (RuntimeException ex) {
            // Visualization is an optional presentation enrichment. Once a substantive answer has
            // been selected, malformed attachment metadata must never erase that answer.
            visualizationSpec = Map.of();
            values.put("toolResultVisualizationProjectionFailed", true);
            values.put("toolResultVisualizationProjectionErrorType", ex.getClass().getSimpleName());
            log.warn("toolResultVisualizationProjectionFailed answerPreserved=true errorType={}",
                ex.getClass().getName());
        }
        if (!visualizationSpec.isEmpty()
            && Boolean.FALSE.equals(values.get("supportingDatasetPrimaryDisplayAllowed"))) {
            visualizationSpec = supportingDatasetSpec(visualizationSpec,
                Boolean.TRUE.equals(values.get("supportingDatasetDefaultCollapsed")));
        }
        if (!visualizationSpec.isEmpty()) {
            values.putIfAbsent("visualizationSpec", visualizationSpec);
            values.putIfAbsent("dataVisualization", visualizationSpec);
            values.put("toolResultDataDisplayed", true);
            values.put("toolResultDataDisplaySource", visualizationSpec.get("sourceTool"));
            boolean governedAnalysisResponse = Boolean.TRUE.equals(values.get("returnedDataAnalysisRequired"))
                || Boolean.TRUE.equals(values.get("governedNarrativeAnalysisAppended"));
            if (userFacingPolicy.supportsStructuredResultPresentation(values) || governedAnalysisResponse) {
                values.put("toolResultPresentationMode", "structured_visualization");
                values.put("toolResultDataMarkdownSuppressed", true);
                if (governedAnalysisResponse) {
                    values.put("toolResultDataMarkdownSuppressionReason", "governed_business_analysis");
                }
            } else {
                String answerWithTable = userFacingPolicy.appendToolResultTable(finalAnswer, visualizationSpec);
                if (!answerWithTable.equals(finalAnswer)) {
                    finalAnswer = answerWithTable;
                    values.put("toolResultPresentationMode", "markdown_fallback");
                    values.put("toolResultDataMarkdownAppended", true);
                    values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
                }
            }
        }
        List<Map<String, Object>> toolEvidence;
        try {
            toolEvidence = userFacingPolicy.toolResultEvidence(traces);
        } catch (RuntimeException ex) {
            // Evidence attachments are secondary to the governed analysis report. Preserve the
            // report and expose a machine-readable diagnostic instead of failing the entire run.
            toolEvidence = List.of();
            values.put("toolResultEvidenceProjectionFailed", true);
            values.put("toolResultEvidenceProjectionErrorType", ex.getClass().getSimpleName());
            log.warn("toolResultEvidenceProjectionFailed answerPreserved=true errorType={}",
                ex.getClass().getName());
        }
        if (!toolEvidence.isEmpty()) {
            values.put("toolResultEvidence", toolEvidence);
            values.put("toolResultEvidenceCount", toolEvidence.size());
            if (userFacingPolicy.shouldExposeToolEvidence(query, values)) {
                String answerWithEvidence = userFacingPolicy.appendToolEvidence(finalAnswer, toolEvidence);
                if (!answerWithEvidence.equals(finalAnswer)) {
                    finalAnswer = answerWithEvidence;
                    values.put("toolResultEvidenceMarkdownAppended", true);
                    values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
                }
            } else {
                values.put("toolResultEvidenceMarkdownAppended", false);
                values.put("toolResultEvidenceMarkdownSuppressed", true);
                if (Boolean.TRUE.equals(values.get("deterministicMandatoryWorkflowFailure"))
                    || Boolean.TRUE.equals(values.get("fatalExecutionBlocked"))) {
                    values.put("failedToolLimitationsSuppressedForExecutionFailure", true);
                } else {
                    finalAnswer = userFacingPolicy.appendFailedToolLimitations(finalAnswer, toolEvidence);
                }
            }
        }
        finalAnswer = userFacingPolicy.applyUserFacingSectionPolicy(finalAnswer, query, values);
        finalAnswer = answerEvidenceAuditService.bindReturnedEvidence(
            finalAnswer, values, observations, toolEvidence, "final_assembly");
        if (!finalAnswer.equals(selectedAnswer == null ? "" : selectedAnswer)) {
            values.put("finalAnswerSanitized", true);
            values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
        }
        answerEvidenceAuditService.recordPosture(
            values, containsEvidence(observations == null ? List.of() : observations), toolEvidence, traces);
        DraftArtifactRuntimePolicy.Result draftArtifact = draftArtifactRuntimePolicy.enforce(
            finalAnswer, values);
        finalAnswer = draftArtifact.answer();
        values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
        logAnswerDecision(decision, values);
        values.put("runtimeContractVersion", "agent_runtime_v1");
        values.put("observations", observations == null ? List.of() : List.copyOf(observations));
        values.put("toolTraceCount", traces == null ? 0 : traces.size());
        attachAnswerAssemblyPolicy(values, observations);
        attachTaskResultAssessment(values, traces, observations);
        attachEvidenceAnswerContract(finalAnswer, values, observations);
        finalAnswer = answerEvidenceAuditService.attachLedger(
            finalAnswer, values, observations, toolEvidence);
        finalAnswer = userFacingPolicy.applyUserFacingEvidenceReferencePolicy(finalAnswer, query, values);
        values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
        attachGovernedSummaryResult(finalAnswer, values, traces, observations);
        String userFacingAnswer = UserFacingAnswerSanitizer.sanitize(finalAnswer);
        if (!userFacingAnswer.equals(finalAnswer)) {
            values.put("userFacingReconciliationDetailsSuppressed", true);
        }
        finalAnswer = userFacingAnswer;
        answerEvidenceAuditService.attachEnvelope(values, query);
        values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
        return new AgentOrchestrator.AgentExecutionResult(
            finalAnswer,
            traces == null ? List.of() : List.copyOf(traces),
            values
        );
    }

    private void attachGovernedSummaryResult(String finalAnswer,
                                             Map<String, Object> values,
                                             List<InteractionToolTrace> traces,
                                             List<String> observations) {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            stringValue(values.get("tenantId")),
            stringValue(values.get("userId")),
            stringValue(values.get("agentRunId")),
            stringValue(values.get("requestId")),
            stringValue(values.get("conversationId"))
        );
        Map<String, Object> upstream = objectMap(values.get("analysisSummaryResult"));
        List<String> upstreamIds = List.of();
        if (!upstream.isEmpty()) {
            Map<String, Object> upstreamScope = objectMap(upstream.get("isolationScope"));
            boolean samePartition = scope.tenantId().equals(stringValue(upstreamScope.get("tenantId")))
                && scope.runId().equals(stringValue(upstreamScope.get("runId")));
            String upstreamId = stringValue(upstream.get("resultId"));
            if (samePartition && upstreamId != null && !upstreamId.isBlank()) {
                upstreamIds = List.of(upstreamId);
                values.put("analysisSummaryUpstreamResult", upstream);
            } else {
                values.put("analysisSummaryUpstreamIsolationRejected", true);
            }
        }
        String outcome = Boolean.TRUE.equals(values.get("deterministicFinalizationFallback"))
            ? "DETERMINISTIC_FINALIZATION"
            : "FINAL_ANSWER_ASSEMBLY";
        AnalysisSummaryResult result = analysisSummaryGovernanceBridge.finalResult(
            scope,
            "answer_finalization",
            finalAnswer,
            outcome,
            Map.of(
                "toolTraceCount", traces == null ? 0 : traces.size(),
                "observationCount", observations == null ? 0 : observations.size(),
                "answerAssemblyComplete", true
            ),
            List.of(),
            upstreamIds
        );
        values.put("analysisSummaryResult", result.toMap());
        values.put("analysisSummaryResultSchemaVersion", AnalysisSummaryResult.SCHEMA_VERSION);
        values.put("analysisSummaryObservable", true);
    }


    @Override
    public boolean markToolBudgetExceeded(String requestedToolName,
                                          int maxToolCalls,
                                          List<InteractionToolTrace> traces,
                                          Map<String, Object> metadata,
                                          List<String> observations) {
        return toolBudgetPolicy.markToolBudgetExceeded(
            requestedToolName, maxToolCalls, traces, metadata, observations);
    }

    public boolean shouldExposeToolEvidence(String query, Map<String, Object> metadata) {
        return userFacingPolicy.shouldExposeToolEvidence(query, metadata);
    }

    public boolean shouldExposeEvidenceReferences(String query, Map<String, Object> metadata) {
        return userFacingPolicy.shouldExposeEvidenceReferences(query, metadata);
    }

    public AgentOrchestrator.AgentExecutionResult finishBudgetedSummary(ChatModel activeChatModel,
                                                                 String query,
                                                                 String systemPrompt,
                                                                 List<InteractionToolTrace> traces,
                                                                 Map<String, Object> metadata,
                                                                 List<String> observations,
                                                                 BooleanSupplier cancellationCheck) {
        runtimeGuard.checkCancelled(cancellationCheck);
        recordMcpResultEvidencePolicy(metadata, traces);
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
        FinalSummaryWebSearchEnhancer.Enhancement enhancement = enhanceFinalSummary(
            activeChatModel, query, systemPrompt, finalAnswer, observations, traces, metadata);
        List<String> effectiveObservations = enhancement.observations();
        List<InteractionToolTrace> effectiveTraces = enhancement.traces();
        finalAnswer = answerEvidenceAuditService.bindReturnedEvidence(finalAnswer, metadata, effectiveObservations,
            userFacingPolicy.toolResultEvidence(effectiveTraces), "pre_review");
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_summary");
        AgentAnswerReview review = answerReviewCoordinator.review(activeChatModel, query, systemPrompt,
            effectiveObservations, finalAnswer, metadata);
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_review");
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", "tool_budget_exceeded");
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal(finalAnswer, effectiveObservations, metadata);
        AnswerQualityEvaluator.QualityReport quality = answerQualityCoordinator.evaluate(
            activeChatModel,
            query,
            systemPrompt,
            effectiveObservations,
            finalAnswer,
            review,
            signal,
            metadata
        );
        return finishWithDecision(activeChatModel, query, systemPrompt, finalAnswer, review, signal, quality,
            effectiveTraces, metadata, effectiveObservations);
    }

    public AgentOrchestrator.AgentExecutionResult finishReviewedSummary(ChatModel activeChatModel,
                                                                 String query,
                                                                 String systemPrompt,
                                                                 List<InteractionToolTrace> traces,
                                                                 Map<String, Object> metadata,
                                                                 List<String> observations,
                                                                 BooleanSupplier cancellationCheck,
                                                                 String stopReason) {
        runtimeGuard.checkCancelled(cancellationCheck);
        recordMcpResultEvidencePolicy(metadata, traces);
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
        FinalSummaryWebSearchEnhancer.Enhancement enhancement = enhanceFinalSummary(
            activeChatModel, query, systemPrompt, finalAnswer, observations, traces, metadata);
        List<String> effectiveObservations = enhancement.observations();
        List<InteractionToolTrace> effectiveTraces = enhancement.traces();
        finalAnswer = answerEvidenceAuditService.bindReturnedEvidence(finalAnswer, metadata, effectiveObservations,
            userFacingPolicy.toolResultEvidence(effectiveTraces), "pre_review");
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_summary");
        AgentAnswerReview review = answerReviewCoordinator.review(activeChatModel, query, systemPrompt,
            effectiveObservations, finalAnswer, metadata);
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_review");
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal(finalAnswer, effectiveObservations, metadata);
        AnswerQualityEvaluator.QualityReport quality = answerQualityCoordinator.evaluate(
            activeChatModel,
            query,
            systemPrompt,
            effectiveObservations,
            finalAnswer,
            review,
            signal,
            metadata
        );
        return finishWithDecision(activeChatModel, query, systemPrompt, finalAnswer, review, signal, quality,
            effectiveTraces, metadata, effectiveObservations);
    }

    public AgentOrchestrator.AgentExecutionResult finishReviewedAnswer(ChatModel activeChatModel,
                                                                String query,
                                                                String systemPrompt,
                                                                List<InteractionToolTrace> traces,
                                                                Map<String, Object> metadata,
                                                                List<String> observations,
                                                                String answer,
                                                                BooleanSupplier cancellationCheck,
                                                                String stopReason) {
        recordMcpResultEvidencePolicy(metadata, traces);
        answerQualityCoordinator.prepareContext(query, systemPrompt, observations, metadata);
        String finalAnswer = safeAnswer(activeChatModel, answer, query, observations, systemPrompt, metadata);
        FinalSummaryWebSearchEnhancer.Enhancement enhancement = enhanceFinalSummary(
            activeChatModel, query, systemPrompt, finalAnswer, observations, traces, metadata);
        List<String> effectiveObservations = enhancement.observations();
        List<InteractionToolTrace> effectiveTraces = enhancement.traces();
        finalAnswer = answerEvidenceAuditService.bindReturnedEvidence(finalAnswer, metadata, effectiveObservations,
            userFacingPolicy.toolResultEvidence(effectiveTraces), "pre_review");
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_answer");
        AgentAnswerReview review = answerReviewCoordinator.review(activeChatModel, query, systemPrompt,
            effectiveObservations, finalAnswer, metadata);
        recordCancellationAfterAnswer(cancellationCheck, metadata, "after_review");
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        AnswerDecisionEngine.EvidenceSignal signal = evidenceSignal(finalAnswer, effectiveObservations, metadata);
        AnswerQualityEvaluator.QualityReport quality = answerQualityCoordinator.evaluate(
            activeChatModel,
            query,
            systemPrompt,
            effectiveObservations,
            finalAnswer,
            review,
            signal,
            metadata
        );
        return finishWithDecision(activeChatModel, query, systemPrompt, finalAnswer, review, signal, quality,
            effectiveTraces, metadata, effectiveObservations);
    }

    public AgentOrchestrator.AgentExecutionResult finishProducedAnswerAfterCancellation(
        String query,
        List<InteractionToolTrace> traces,
        Map<String, Object> metadata,
        List<String> observations,
        String answer,
        String reason
    ) {
        recordAnswerCompletedAfterCancellation(
            metadata,
            "after_planner_answer",
            firstNonBlank(reason, "Agent deadline reached after a displayable answer was produced")
        );
        metadata.put("stopReason", "answer_completed_after_cancellation");
        metadata.put("resultRecoveredAtDeadline", true);
        return finishWithDecision(null, query, null, answer, null, null, null,
            traces, metadata, observations);
    }

    private FinalSummaryWebSearchEnhancer.Enhancement enhanceFinalSummary(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        String candidateAnswer,
        List<String> observations,
        List<InteractionToolTrace> traces,
        Map<String, Object> metadata
    ) {
        String runId = stringValue(metadata == null ? null : metadata.get("agentRunId"));
        try {
            FinalSummaryWebSearchEnhancer.Enhancement enhancement = runWithTimeout(
                "final-summary-web-search",
                firstNonBlank(runId, ""),
                agentRuntimeProperties.finalSummaryWebSearchTimeoutMs(),
                () -> finalSummaryWebSearchEnhancer.enhance(
                    activeChatModel, query, systemPrompt, candidateAnswer, observations, traces, metadata)
            );
            if (enhancement.used() && enhancement.enhancedAnswer() != null
                && !enhancement.enhancedAnswer().isBlank()) {
                answerQualityCoordinator.registerCandidate(
                    metadata,
                    FinalSummaryWebSearchEnhancer.CANDIDATE_STAGE,
                    enhancement.enhancedAnswer(),
                    List.of(),
                    Map.of("source", "tencent_wsa", "internalEnhancement", true)
                );
            }
            return enhancement;
        } catch (TimeoutException ex) {
            if (metadata != null) {
                metadata.put("finalSummaryWebSearchTimedOut", true);
                metadata.put("finalSummaryWebSearchUsed", false);
            }
            log.warn("agentModelTimeout phase=final_summary_web_search runId={} timeoutMs={}",
                firstNonBlank(runId, ""), agentRuntimeProperties.finalSummaryWebSearchTimeoutMs());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (metadata != null) {
                metadata.put("finalSummaryWebSearchInterrupted", true);
                metadata.put("finalSummaryWebSearchUsed", false);
            }
        } catch (Exception ex) {
            if (metadata != null) {
                metadata.put("finalSummaryWebSearchFailed", true);
                metadata.put("finalSummaryWebSearchFailure", firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName()));
                metadata.put("finalSummaryWebSearchUsed", false);
            }
            log.warn("agentModelFailed phase=final_summary_web_search runId={} error={}",
                firstNonBlank(runId, ""), ex.getMessage());
        }
        return FinalSummaryWebSearchEnhancer.Enhancement.skipped(observations, traces);
    }

    private void recordCancellationAfterAnswer(BooleanSupplier cancellationCheck,
                                               Map<String, Object> metadata,
                                               String phase) {
        if (cancellationCheck == null) {
            return;
        }
        try {
            if (cancellationCheck.getAsBoolean()) {
                recordAnswerCompletedAfterCancellation(metadata, phase, "Agent cancellation requested after answer was produced");
            }
        } catch (CancellationException ex) {
            recordAnswerCompletedAfterCancellation(metadata, phase, firstNonBlank(ex.getMessage(), "Agent cancellation requested after answer was produced"));
        }
    }

    private void recordAnswerCompletedAfterCancellation(Map<String, Object> metadata,
                                                        String phase,
                                                        String reason) {
        if (metadata == null) {
            return;
        }
        metadata.put("answerCompletedAfterCancellation", true);
        metadata.put("answerCancellationPhase", phase);
        metadata.put("answerCancellationReason", reason);
        metadata.putIfAbsent("stopReason", "answer_completed_after_cancellation");
    }

    private String safeAnswer(ChatModel activeChatModel,
                              String answer,
                              String query,
                              List<String> observations,
                              String systemPrompt,
                              Map<String, Object> metadata) {
        if (answer != null && !answer.isBlank()) {
            String sanitized = sanitizeFinalMarkdown(answer);
            if (!sanitized.isBlank()) {
                return sanitized;
            }
            if (metadata != null) {
                metadata.put("userFacingAnswerRegenerationRequired", true);
                metadata.put("userFacingAnswerRegenerationReason", "internal_protocol_or_unreadable_answer");
            }
        }
        return summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
    }

    private String summarizeWithObservations(ChatModel activeChatModel,
                                             String query,
                                             String systemPrompt,
                                             List<String> observations,
                                             Map<String, Object> metadata) {
        if (activeChatModel == null) {
            if (metadata != null) {
                metadata.put("summarySkipped", "chat_model_unavailable");
            }
            return "";
        }
        AnswerQualityCoordinator.QualityContext qualityContext = answerQualityCoordinator.prepareContext(
            query, systemPrompt, observations, metadata);
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        prompt.append("Produce the highest-quality user-facing answer that satisfies the Answer Contract.\n");
        prompt.append("Answer Contract:\n").append(qualityContext.contract().promptText()).append("\n");
        prompt.append("Evidence sufficiency gate:\n").append(qualityContext.gate().promptText()).append("\n");
        prompt.append("Follow outputFormat and language from the Answer Contract exactly. For AUTO or MARKDOWN, use polished Markdown with concise headings and lists when useful.\n");
        prompt.append("Do not wrap the response in code fences. Output JSON only when the Answer Contract explicitly requires JSON.\n");
        prompt.append("First understand the user's intent, then synthesize the evidence into a clear explanation instead of copying tool output or internal execution reports.\n");
        prompt.append("Preserve successful structured results even when incomplete or unexpected; present observed data before limitations and next checks.\n");
        boolean userVisibleEvidence = userFacingPolicy.shouldExposeEvidenceReferences(query, metadata)
            || AnswerContract.EVIDENCE_REQUIRED.equals(qualityContext.contract().evidencePolicy());
        prompt.append("Treat data analysis as the primary deliverable and evidence mechanics as hidden support. Unless the user explicitly requests provenance, auditing, citations, API debugging, or commands, do not include dedicated sections for evidence chains, sources, API endpoints, tool calls, execution facts, diagnostic workflow, verification commands, or troubleshooting steps.\n");
        prompt.append("For analysis requests, lead with concrete returned values, comparisons, distributions, anomalies, and their meaning. HTTP success, endpoint reachability, query completion, record counts, and coverage percentages are not substitutes for analytical findings. Never say values need to be expanded when the observations already contain them.\n");
        prompt.append("Derive analytical dimensions, comparisons, conclusions, and headings only from the user's request, the actual returned fields and values, and supplied analysisContext. Do not impose a domain-specific framework or canned report structure that those inputs do not establish.\n");
        prompt.append("Analyze returned business data rather than the evidence mechanism. Source, citation, trust, tool, and execution fields are internal grounding support and must remain secondary unless the user explicitly requests provenance or runtime diagnostics.\n");
        prompt.append("Do not turn counter equality, status coexistence, or completed execution into causal claims such as normal completion, health, or absence of failures unless the returned fields explicitly establish that conclusion. State the observed counters first and label any broader interpretation as inference.\n");
        prompt.append("Keep execution success separate from evidence sufficiency. A successful result remains reportable while weak coverage lowers confidence.\n");
        prompt.append("Respect evidence semantics supplied at runtime, including scope, time basis, completeness, capability and source role. Do not infer beyond them.\n");
        if (userVisibleEvidence) {
            prompt.append("Keep an exact returned evidence URI or source citation near every factual numeric, date, causal, comparative, or definitive claim. A tool display name alone is not an evidence reference.\n");
        } else {
            prompt.append("Use returned evidence to ground every factual claim internally, but do not emit tool://, doc://, web://, evidenceId, [网页N], or [evidence: ...] markers in the user-facing answer. Runtime retains claim bindings and sources in metadata.\n");
        }
        prompt.append("If any tool observation reports failure, explicitly state that this source was unavailable and do not treat it as evidence.\n");
        if (userVisibleEvidence) {
            prompt.append("If observations include evidence_v1 Unified evidence context, use only those EvidenceChunk entries as grounded evidence and keep the matching citation near every claim that relies on that evidence.\n");
        } else {
            prompt.append("If observations include evidence_v1 Unified evidence context, use only those EvidenceChunk entries for internal grounding. Do not describe selected/rejected chunks, retrieval coverage, document outlines, or evidence evaluation in the user-facing answer.\n");
        }
        prompt.append("When both internal document and web search observations are available, separate internal document evidence from web verification evidence and explain conflicts instead of merging them silently.\n");
        if (userVisibleEvidence) {
            prompt.append("If observations include document_evidence_v1, document evidence context, or document citations, keep the matching document citation near every claim that relies on that evidence.\n");
        } else {
            prompt.append("If observations include document_evidence_v1, document evidence context, or document citations, use them silently for grounding. Return business conclusions only; keep filenames, section/chunk selection, rejected documents, and citation mechanics out of the answer.\n");
        }
        prompt.append("If observations include evidence_v1 or document_evidence_v1, use the evidence to write Markdown; do not emit an EvidenceAnswer object.\n");
        prompt.append("If observations include evidence_execution_contract_v2_2 Deterministic answer lock, treat lockedAnswer and reasoningPayload as grounded evidence constraints, not as text to copy verbatim.\n");
        if (!qualityContext.gate().strongClaimsAllowed()) {
            prompt.append("Evidence gate policy: do not make strong factual conclusions. State the evidence gap and provide only supported observations or a bounded next step.\n");
        } else if (qualityContext.gate().retrieveMoreRecommended()) {
            prompt.append("Evidence gate policy: answer from usable evidence, identify unresolved gaps explicitly, and avoid filling them with assumptions.\n");
        }
        if (userVisibleEvidence) {
            prompt.append("If observations include web citation labels, append the matching label immediately after every sentence that relies on that web source.\n");
        }
        prompt.append("Do not invent citations or cite URLs that are not listed in the observations.\n");
        prompt.append("Before writing, internally enumerate every material factual claim. Bind each numeric, date, causal, comparative, and definitive claim to the exact returned evidence reference, and keep that reference immediately after the supported sentence. If returned evidence does not support a claim, weaken it explicitly or move it to limitations instead of presenting it as fact.\n");
        prompt.append("Do not use one citation as decoration for a paragraph containing unrelated claims. Preserve conflicts and distinguish verified facts, reasoned interpretation, and missing evidence.\n");
        prompt.append("If an Evidence trust policy asks for more evidence, avoid strong claims and say that trusted evidence is insufficient.\n");
        prompt.append(AgentRuntimeFactGroundingContract.promptSection());
        if (containsEvidence(observations == null ? List.of() : observations)
            || mcpResultAvailable(metadata)) {
            AnswerAssemblyPolicy assemblyPolicy = answerAssemblyEngine.plan(
                observations, mcpResultAvailable(metadata));
            prompt.append(answerAssemblyEngine.promptInstructions(assemblyPolicy)).append("\n");
        }
        TaskResultAssessment taskAssessment = taskResultAssessmentCompiler.compile(
            metadata,
            List.of(),
            answerAssemblyPolicy(observations, metadata)
        );
        if (metadata != null) {
            metadata.put(TaskResultAssessmentCompiler.METADATA_KEY, taskAssessment.toMap());
        }
        String taskResultInstructions = taskResultAssessmentCompiler.promptInstructions(taskAssessment);
        if (!taskResultInstructions.isBlank()) {
            prompt.append(taskResultInstructions).append("\n");
        }
        if (observations == null || observations.isEmpty()) {
            prompt.append("No external tool observation is available.\n");
        } else {
            observations.forEach(ob -> prompt.append("- ").append(ob).append("\n"));
        }
        prompt.append("\nUser question: ").append(query);
        String promptText = prompt.toString();
        String runId = stringValue(metadata == null ? null : metadata.get("agentRunId"));
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=summary runId={} modelClass={} promptChars={} observationCount={}",
            firstNonBlank(runId, ""),
            activeChatModel == null ? null : activeChatModel.getClass().getName(),
            promptText.length(),
            observations == null ? 0 : observations.size());
        String answer;
        try {
            answer = activeChatModel.chat(promptText);
        } catch (RuntimeException ex) {
            if (metadata != null) {
                metadata.put("summaryGenerated", false);
                metadata.put("summaryFailure", firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName()));
                metadata.put("summaryFallback", "deterministic_tool_evidence");
            }
            log.warn("agentModelFailed phase=summary runId={} promptChars={} errorType={} error={}",
                firstNonBlank(runId, ""),
                promptText.length(),
                ex.getClass().getSimpleName(),
                ex.getMessage());
            return "";
        }
        log.info("agentModelResponse phase=summary runId={} durationMs={} responseChars={}",
            firstNonBlank(runId, ""),
            System.currentTimeMillis() - startedAt,
            answer == null ? 0 : answer.length());
        log.info("agentModelOutput phase=summary runId={} answer=\n{}",
            firstNonBlank(runId, ""),
            ModelProtocolJson.prettyJsonForLog(answer));
        return sanitizeFinalMarkdown(answer);
    }

    private String sanitizeFinalMarkdown(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String text = answer.trim();
        if (containsInternalEvidenceProtocol(text)) {
            return "";
        }
        text = userFacingPolicy.stripOuterFenceIfPresent(text, "json");
        String jsonAnswer = extractUserAnswerFromJson(text);
        if (jsonAnswer != null && !jsonAnswer.isBlank()) {
            text = jsonAnswer.trim();
        } else if (userFacingPolicy.looksLikeJson(text)) {
            text = "已完成分析，但模型返回的是内部调试 JSON，已隐藏原始结构化内容。";
        }
        text = text.replaceAll("(?is)reasoningPayload:\\s*```json\\s*.*?\\s*```", "").trim();
        text = text.replaceAll("(?is)```json\\s*.*?\\s*```", "").trim();
        text = text.replaceAll("(?im)^\\s*(reasoningPayload|executionDag|reasoningTrace|trustedSql|deterministicFacts)\\s*:\\s*$", "").trim();
        text = text.replace(DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER, "")
            .replace(DeterministicAnswerCompiler.END_LOCKED_ANSWER, "")
            .trim();
        if (text.startsWith("```markdown")) {
            text = userFacingPolicy.stripOuterFence(text, "```markdown");
        } else if (text.startsWith("```md")) {
            text = userFacingPolicy.stripOuterFence(text, "```md");
        }
        return text.trim();
    }

    @SuppressWarnings("unchecked")
    private String extractUserAnswerFromJson(String text) {
        if (!userFacingPolicy.looksLikeJson(text)) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(text, new TypeReference<>() {
            });
            Object uiResponse = payload.get("uiResponse");
            if (uiResponse instanceof Map<?, ?> uiMap) {
                String answer = stringValue(((Map<String, Object>) uiMap).get("answer"));
                String citations = userFacingPolicy.citationsText(((Map<String, Object>) uiMap).get("citations"));
                if (answer != null && !answer.isBlank()) {
                    return appendCitations(answer, citations);
                }
            }
            String answer = stringValue(payload.get("answer"));
            String citations = userFacingPolicy.citationsText(payload.get("citations"));
            if (answer != null && !answer.isBlank()) {
                return appendCitations(answer, citations);
            }
            String summary = stringValue(payload.get("evidenceSummary"));
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String appendCitations(String answer, String citations) {
        if (citations == null || citations.isBlank()) {
            return answer;
        }
        if (answer.contains(citations)) {
            return answer;
        }
        return answer.trim() + "\n\n引用：" + citations;
    }


    private <T> T runWithTimeout(String phase,
                                 String runId,
                                 long timeoutMs,
                                 Callable<T> task) throws Exception {
        if (timeoutMs <= 0) {
            return task.call();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                "agent-" + firstNonBlank(phase, "model") + "-" + firstNonBlank(runId, "unknown"));
            thread.setDaemon(true);
            return thread;
        });
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private long configuredTimeoutMs(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long modelRequestTimeoutMs(ModelsConfig modelsConfig) {
        if (modelsConfig == null) {
            return 0L;
        }
        ModelsConfig.ModelConnectionConfig connection = modelsConfig.resolveChatModelConfig(
            modelsConfig.getDefaultChatModel());
        if (connection == null) {
            return 0L;
        }
        int timeout = connection.getTimeout();
        if (timeout <= 0) {
            return 0L;
        }
        return timeout >= 1000 ? timeout : TimeUnit.SECONDS.toMillis(timeout);
    }

    private void recordAnswerReview(Map<String, Object> metadata, AgentAnswerReview review) {
        if (metadata == null || review == null) {
            return;
        }
        metadata.put("answerReviewStatus", review.status());
        if (review.feedback() != null && !review.feedback().isBlank()) {
            metadata.put("answerReviewFeedback", review.feedback());
        }
        if (AgentAnswerReview.REVISED.equals(review.status())
            && review.answer() != null
            && !review.answer().isBlank()) {
            metadata.put("answerReviewRewriteSuggested", true);
            metadata.put("answerReviewSuggestedAnswerPreview", shortText(review.answer(), 1000));
        }
    }

    private void logAnswerDecision(AnswerDecisionEngine.AnswerDecision decision, Map<String, Object> metadata) {
        if (decision == null || AnswerDecisionEngine.NO_REWRITE.equals(decision.action())) {
            return;
        }
        log.warn("agentAnswerDecision action={} source={} reason={} finalAnswerPreview={}",
            decision.action(),
            decision.rewriteSource(),
            decision.reason(),
            metadata == null ? null : metadata.get("finalAnswerPreview"));
    }

    private void attachEvidenceAnswerContract(String answer,
                                              Map<String, Object> metadata,
                                              List<String> observations) {
        List<String> safeObservations = observations == null ? List.of() : observations;
        if (!groundingGuard.containsEvidence(safeObservations)) {
            return;
        }
        EvidenceAnswerGroundingGuard.GroundingResult result = groundingGuard.guard(answer, safeObservations);
        metadata.put("answerContractVersion", result.contractVersion());
        metadata.put("evidenceAnswer", result.evidenceAnswer().toMap());
        metadata.put("availableEvidenceCitations", result.availableCitations());
        metadata.put("groundingStatus", result.groundingStatus());
    }

    private void attachAnswerAssemblyPolicy(Map<String, Object> metadata, List<String> observations) {
        if (metadata == null) {
            return;
        }
        boolean mcpResultAvailable = mcpResultAvailable(metadata);
        if (!containsEvidence(observations == null ? List.of() : observations)
            && !mcpResultAvailable) {
            return;
        }
        AnswerAssemblyPolicy policy =
            answerAssemblyEngine.plan(observations, mcpResultAvailable);
        metadata.put("answerAssemblyPolicy", policy.toMap());
        metadata.put("answerAssemblyMode", policy.mode().name());
        if (!policy.missingInfo().isEmpty()) {
            metadata.put("answerAssemblyMissingInfo", policy.missingInfo());
        }
    }

    private McpResultEvidencePolicy.Assessment recordMcpResultEvidencePolicy(
        Map<String, Object> metadata,
        List<InteractionToolTrace> traces
    ) {
        McpResultEvidencePolicy.Assessment assessment =
            mcpResultEvidencePolicy.assess(traces);
        if (metadata == null) {
            return assessment;
        }
        metadata.put("mcpResultEvidencePolicyContractVersion", assessment.contractVersion());
        metadata.put("mcpResultEvidenceAvailability", assessment.availability().name());
        metadata.put("mcpTotalToolCount", assessment.totalToolCount());
        metadata.put("mcpSuccessfulToolCount", assessment.successfulToolCount());
        metadata.put("mcpFailedToolCount", assessment.failedToolCount());
        metadata.put("mcpAvailableResultCount", assessment.availableResultCount());
        metadata.put("mcpEmptyResultCount", assessment.emptyResultCount());
        metadata.put("mcpUnavailableResultCount", assessment.unavailableResultCount());
        metadata.put("mcpResultAnswerAllowed", assessment.resultAvailable());
        metadata.put("mcpResultAnalysisCapability", assessment.availability()
            == McpResultEvidencePolicy.Availability.AVAILABLE ? "FULL"
            : assessment.availability() == McpResultEvidencePolicy.Availability.PARTIAL ? "PARTIAL" : "NONE");
        return assessment;
    }

    private String enforceMcpResultAnalysisPolicy(
        String candidateAnswer,
        McpResultEvidencePolicy.Assessment assessment,
        Map<String, Object> metadata
    ) {
        if (assessment == null) {
            return candidateAnswer;
        }
        if (assessment.availability() == McpResultEvidencePolicy.Availability.EMPTY
            && assessment.successfulToolCount() > 0) {
            if (metadata != null) {
                metadata.put("emptyResultGroundingApplied", true);
                metadata.put("emptyResultGroundingReason", "successful_mcp_query_returned_no_records");
            }
            return preserveCandidateWithEvidenceLimitation(
                candidateAnswer,
                emptyMcpResultAnswer(),
                "工具查询成功，但未返回匹配记录。以下内容仅保留已有分析框架，不能据此补造事实、数值或趋势。",
                metadata
            );
        }
        if (assessment.availability() == McpResultEvidencePolicy.Availability.UNAVAILABLE
            && assessment.totalToolCount() > 0) {
            if (metadata != null) {
                metadata.put("invalidResultGroundingApplied", true);
                metadata.put("invalidResultGroundingReason", "mcp_output_unavailable_or_malformed");
            }
            return preserveCandidateWithEvidenceLimitation(
                candidateAnswer,
                unavailableMcpResultAnswer(),
                "结构化工具结果当前不可解析或不可验证。以下分析仅基于其他已获得信息或一般分析框架，实时事实与数值尚未完成验证。",
                metadata
            );
        }
        if (assessment.availability() == McpResultEvidencePolicy.Availability.PARTIAL) {
            if (metadata != null) {
                metadata.put("partialResultGroundingApplied", true);
                metadata.put("partialResultGroundingReason", "mixed_available_and_missing_mcp_evidence");
            }
            String partialCandidate = candidateAnswer;
            if (assessment.resultAvailable() && refusesAnalysis(candidateAnswer)) {
                if (metadata != null) {
                    metadata.put("evidenceRefusalBlocked", true);
                    metadata.put("evidenceRefusalBlockedReason",
                        "partial_non_empty_mcp_result_with_insufficient_evidence_refusal");
                    metadata.put("originalRefusalPreview", shortText(candidateAnswer, 1000));
                }
                return analysisFailureReport(metadata,
                    "PARTIAL_MCP_RESULT_WITHOUT_PUBLISHABLE_REPORT");
            }
            return preserveCandidateWithEvidenceLimitation(
                partialCandidate,
                unavailableMcpResultAnswer(),
                "工具结果仅部分覆盖本次需求。以下分析保留已获得且可验证的内容；缺失、超时或不可解析部分不作为事实依据。",
                metadata
            );
        }
        if (!assessment.resultAvailable()) {
            return candidateAnswer;
        }
        String answer = candidateAnswer == null ? "" : candidateAnswer.trim();
        if (answer.isBlank()) {
            return candidateAnswer;
        }
        if (!refusesAnalysis(answer)) {
            return candidateAnswer;
        }
        if (metadata != null) {
            metadata.put("evidenceRefusalBlocked", true);
            metadata.put("evidenceRefusalBlockedReason",
                answer.isBlank()
                    ? "non_empty_mcp_result_with_empty_answer"
                    : "non_empty_mcp_result_with_insufficient_evidence_refusal");
            metadata.put("originalRefusalPreview", shortText(answer, 1000));
        }
        return analysisFailureReport(metadata,
            "MCP_RESULT_WITHOUT_PUBLISHABLE_REPORT");
    }

    private String preserveCandidateWithEvidenceLimitation(String candidateAnswer,
                                                           String emptyFallback,
                                                           String limitation,
                                                           Map<String, Object> metadata) {
        String answer = candidateAnswer == null ? "" : candidateAnswer.trim();
        if (answer.isBlank()) {
            return emptyFallback;
        }
        boolean runtimeConfirmedPartialEvidence = metadata != null
            && (Boolean.TRUE.equals(metadata.get("partialResultGroundingApplied"))
                || "evidence_partial_analysis".equals(String.valueOf(metadata.get("stopReason")))
                || "partial_result".equals(String.valueOf(metadata.get("interpretationPlanFallbackMode")))
                || Boolean.TRUE.equals(metadata.get("interpretationPlanWorkflowBlocked"))
                || Boolean.TRUE.equals(metadata.get("mandatoryWorkflowBlocked"))
                || Boolean.TRUE.equals(metadata.get("fatalExecutionBlocked")));
        if (!runtimeConfirmedPartialEvidence) {
            return emptyFallback;
        }
        if (metadata != null) {
            metadata.put("evidenceLimitedAnalysisPreserved", true);
            metadata.put("evidenceLimitation", limitation);
        }
        if (answer.contains("数据覆盖说明")) {
            return answer;
        }
        return answer + "\n\n> 数据覆盖说明：" + limitation;
    }

    private String emptyMcpResultAnswer() {
        return "查询已成功执行，但没有返回匹配记录。当前没有可用于事实判断或趋势分析的数据，"
            + "因此不能据此推断数值、趋势或建议。请调整查询条件、时间范围，或确认模板参数后重试。";
    }

    private String unavailableMcpResultAnswer() {
        return "工具调用没有产生可解析、可信的结果，可能是超时、错误页或返回协议损坏。"
            + "当前不能据此推断事实、数值、趋势或建议；请检查工具状态与返回协议后重试。";
    }

    private String enforceAnalysisReportContract(String candidateAnswer,
                                                 Map<String, Object> metadata) {
        Map<String, Object> contract = objectMap(metadata.get("analysisReportContract"));
        String candidate = candidateAnswer == null ? "" : candidateAnswer.trim();
        String renderedText = stringValue(contract.get("renderedText")).trim();
        String reportType = stringValue(contract.get("reportType"));
        boolean supportedSchema = AnalysisReportContract.SCHEMA_VERSION.equals(
            stringValue(contract.get("schemaVersion")));
        boolean candidateIsNarrative = !candidate.isBlank()
            && !AnalysisReportContract.isInternalOrTechnicalPayload(candidate);
        boolean renderedTextIsNarrative = !renderedText.isBlank()
            && !AnalysisReportContract.isInternalOrTechnicalPayload(renderedText);
        if (candidateIsNarrative) {
            recordPresentableAnalysis(metadata, reportType);
            log.info("analysisFinalPayloadClassification reportType={} schemaSupported={} "
                    + "candidateChars={} action=preserve_for_human_review",
                reportType, supportedSchema, candidate.length());
            return candidate;
        }
        if (renderedTextIsNarrative) {
            recordPresentableAnalysis(metadata, reportType);
            metadata.put("finalPayloadRecoveredFromContract", true);
            return renderedText;
        }
        metadata.put("finalPayloadContractAdmitted", false);
        metadata.put("finalPayloadContractRejectionReason", "INTERNAL_OR_EMPTY_PAYLOAD");
        metadata.put("rejectedFinalPayloadType", reportType);
        log.warn("analysisFinalPayloadClassification reportType={} schemaSupported={} "
                + "action=discard_internal_or_empty_payload",
            reportType, supportedSchema);
        return analysisFailureReport(metadata, "FINAL_PAYLOAD_NOT_ANALYSIS");
    }

    /**
     * A later Driver/reviewer narrative supersedes an earlier synthesis fallback. Clear the
     * fail-closed markers here so stale intermediate state cannot turn a presentable,
     * human-reviewable report into a FAILED public outcome.
     */
    private void recordPresentableAnalysis(Map<String, Object> metadata, String reportType) {
        metadata.put("finalPayloadContractAdmitted", true);
        metadata.put("finalPayloadType", reportType);
        metadata.put("finalPayloadHumanReviewRequired", true);
        metadata.put("finalPayloadContractMode", "PROVENANCE_ONLY");
        if ("FAILURE_REPORT".equals(reportType)) {
            return;
        }
        metadata.put("analysisOutputAdmitted", true);
        metadata.put("rawAnalysisOutputWithheld", false);
        metadata.put("analysisSynthesisBlocked", false);
        metadata.put("evidenceRefusalBlocked", false);
        metadata.put("interpretationPlanSummaryGenerated", true);
        metadata.put("interpretationPlanFinalResultProduced", true);
        metadata.put("executionStatus", "PARTIAL_RESULT_PRESENTED");
        metadata.put("analysisExecutionStatus", "COMPLETED_WITH_HUMAN_REVIEW");
        metadata.remove("finalPayloadContractRejectionReason");
        metadata.remove("evidenceRefusalBlockedReason");
    }

    private String analysisFailureReport(Map<String, Object> metadata, String reason) {
        String report = """
            # 数据分析暂时不可用

            本轮数据获取已完成，但分析模型没有生成可解析的业务分析正文。系统已保留数据和分析轨迹，未将内部指令或工具协议展示为分析内容。

            ## 后续处理

            - 复用现有数据重新执行分析模型，不重新查询相同数据。
            - 证据强弱和分析缺口仅作为人工复核提示，不作为系统阻断条件。
            """.trim();
        if (metadata != null) {
            AnalysisReportContract contract = AnalysisReportContract.failureReport(report);
            metadata.put("analysisReportContract", contract.toMap());
            metadata.put("analysisReportContractSchemaVersion",
                AnalysisReportContract.SCHEMA_VERSION);
            metadata.put("finalPayloadType", contract.reportType().name());
            metadata.put("finalPayloadFallbackReason", reason);
            metadata.put("returnedDataAnalysisRequired", true);
            metadata.put("rawAnalysisOutputWithheld", true);
            metadata.put("supportingDatasetPrimaryDisplayAllowed", false);
            metadata.put("supportingDatasetDefaultCollapsed", true);
        }
        return report;
    }

    private boolean refusesAnalysis(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        AnswerQualityAssessment quality = assessAnswerQuality(answer);
        if (!quality.requiresFallback()) {
            return false;
        }
        String normalized = answer.toLowerCase(Locale.ROOT);
        return containsAny(normalized,
            "无法分析", "不能分析", "无法生成完整", "无法生成报告", "无法给出分析",
            "证据不足", "数据不足", "信息不足", "关键数据缺失", "数据完全缺失",
            "insufficient evidence", "unable to analyze", "cannot analyze",
            "cannot generate", "not enough data");
    }

    private boolean mcpResultAvailable(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        Object value = metadata.get("mcpResultAnswerAllowed");
        return Boolean.TRUE.equals(value)
            || (value != null && Boolean.parseBoolean(String.valueOf(value)));
    }

    private void recordSelectedAnswerCandidate(Map<String, Object> metadata,
                                               AnswerDecisionEngine.AnswerDecision decision) {
        if (metadata == null || decision == null || decision.finalAnswer() == null
            || decision.finalAnswer().isBlank()) {
            return;
        }
        RuntimeAnswerCandidate selected;
        Object current = metadata.get("answerCandidate");
        if (current instanceof RuntimeAnswerCandidate candidate
            && decision.finalAnswer().equals(candidate.content())) {
            selected = candidate.transition(
                RuntimeAnswerCandidate.Status.SELECTED,
                Map.of("selectionReason", decision.reason())
            );
        } else {
            selected = new RuntimeAnswerCandidate(
                RuntimeAnswerCandidate.CONTRACT_VERSION,
                decision.finalAnswer(),
                "final_answer",
                "none".equals(decision.rewriteSource())
                    ? "runtime_candidate" : decision.rewriteSource(),
                RuntimeAnswerCandidate.Status.SELECTED,
                Map.of(
                    "selectionReason", decision.reason(),
                    "decision", decision.action()
                )
            );
        }
        metadata.put("answerCandidate", selected);
        metadata.put("answerCandidateContractVersion", selected.contractVersion());
        metadata.put("answerLifecycleStatus", selected.status().name());
        metadata.put("answerOrigin", selected.source());
    }

    private void attachTaskResultAssessment(
                                            Map<String, Object> metadata,
                                            List<InteractionToolTrace> traces,
                                            List<String> observations) {
        if (metadata == null) {
            return;
        }
        TaskResultAssessment assessment = taskResultAssessmentCompiler.compile(
            metadata,
            traces,
            answerAssemblyPolicy(observations, metadata)
        );
        metadata.put(TaskResultAssessmentCompiler.METADATA_KEY, assessment.toMap());
        metadata.put("taskResultAssessmentContractVersion", assessment.contractVersion());
        metadata.put("taskResultExecutionStatus", assessment.execution().status().name());
        metadata.put("taskResultEvidenceStatus", assessment.evidence().status().name());
        metadata.put("taskResultEvidenceAvailability", assessment.evidence().availability().name());
        metadata.put("taskResultAnalysisCapability", assessment.evidence().analysisCapability().name());
        metadata.put("taskResultAnswerAllowed", assessment.evidence().answerAllowed());
        if (assessment.evidence().blockingReason() != null) {
            metadata.put("taskResultBlockingReason", assessment.evidence().blockingReason());
        }
        metadata.put("taskResultFulfillmentStatus", assessment.fulfillment().status().name());
        metadata.put("taskResultDeliveryDecision", assessment.delivery().decision().name());
    }

    private AnswerAssemblyPolicy answerAssemblyPolicy(List<String> observations,
                                                      Map<String, Object> metadata) {
        List<String> safeObservations = observations == null ? List.of() : observations;
        boolean mcpResultAvailable = mcpResultAvailable(metadata);
        return containsEvidence(safeObservations) || mcpResultAvailable
            ? answerAssemblyEngine.plan(safeObservations, mcpResultAvailable)
            : null;
    }

    private boolean containsEvidence(List<String> observations) {
        return observations.stream()
            .filter(value -> value != null)
            .anyMatch(value -> value.contains(UNIFIED_EVIDENCE_CONTRACT)
                || value.contains(DOCUMENT_EVIDENCE_CONTRACT)
                || value.contains(EXECUTION_CONTRACT)
                || value.contains("doc://")
                || value.contains("web://"));
    }

    private AnswerDecisionEngine.EvidenceSignal evidenceSignal(String answer,
                                                               List<String> observations,
                                                               Map<String, Object> metadata) {
        if (Boolean.TRUE.equals(metadata == null ? null : metadata.get("confirmationRequired"))) {
            return new AnswerDecisionEngine.EvidenceSignal(
                true,
                null,
                null,
                List.of(),
                null,
                false,
                null
            );
        }
        AnswerDecisionEngine.DeterministicLockedAnswer lockedAnswer = extractDeterministicLockedAnswer(observations);
        List<AnswerDecisionEngine.GroundedDocumentEvidence> documentEvidence =
            extractGroundedDocumentEvidence(observations);
        AnswerQualityAssessment assessment = assessAnswerQuality(answer);
        if (metadata != null) {
            metadata.put("answerQualityAssessment", assessment.asMetadata());
        }
        boolean shouldReplace = assessment.requiresFallback();
        if (lockedAnswer != null && lockedAnswer.answer() != null && !lockedAnswer.answer().isBlank()) {
            return new AnswerDecisionEngine.EvidenceSignal(
                false,
                EXECUTION_CONTRACT,
                lockedAnswer,
                documentEvidence,
                documentEvidence.isEmpty() ? null : groundedEvidenceAnswer(documentEvidence),
                shouldReplace,
                shouldReplace
                    ? (answer == null || answer.isBlank()
                        ? "empty_answer_with_document_evidence"
                        : "no_match_fallback_with_document_evidence")
                    : null
            );
        }
        if (documentEvidence.isEmpty()) {
            return AnswerDecisionEngine.EvidenceSignal.empty();
        }
        return new AnswerDecisionEngine.EvidenceSignal(
            false,
            null,
            null,
            documentEvidence,
            groundedEvidenceAnswer(documentEvidence),
            shouldReplace,
            shouldReplace
                ? (answer == null || answer.isBlank() ? "empty_answer_with_document_evidence" : "no_match_fallback_with_document_evidence")
                : null
        );
    }

    private AnswerDecisionEngine.DeterministicLockedAnswer extractDeterministicLockedAnswer(List<String> observations) {
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        for (String observation : observations) {
            AnswerDecisionEngine.DeterministicLockedAnswer lockedAnswer = extractDeterministicLockedAnswer(observation);
            if (lockedAnswer != null) {
                return lockedAnswer;
            }
        }
        return null;
    }

    private AnswerDecisionEngine.DeterministicLockedAnswer extractDeterministicLockedAnswer(String observation) {
        if (observation == null || observation.isBlank() || !observation.contains(EXECUTION_CONTRACT)) {
            return null;
        }
        int begin = observation.indexOf(DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER);
        int end = observation.indexOf(DeterministicAnswerCompiler.END_LOCKED_ANSWER);
        if (begin < 0 || end <= begin) {
            return null;
        }
        int answerStart = begin + DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER.length();
        String lockedAnswer = observation.substring(answerStart, end).trim();
        if (lockedAnswer.isBlank()) {
            return null;
        }
        return new AnswerDecisionEngine.DeterministicLockedAnswer(
            lockedAnswer,
            extractLineValue(observation, "contractHash:"),
            extractLineValue(observation, "graphViewHash:")
        );
    }

    private String extractLineValue(String text, String prefix) {
        if (text == null || prefix == null) {
            return null;
        }
        for (String rawLine : text.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private boolean shouldReplaceWithGroundedEvidence(String answer) {
        return assessAnswerQuality(answer).requiresFallback();
    }

    private AnswerQualityAssessment assessAnswerQuality(String answer) {
        boolean trulyEmpty = answer == null || answer.isBlank();
        if (trulyEmpty) {
            return new AnswerQualityAssessment(false, false, false, false, true, false);
        }
        boolean internalProtocol = containsInternalEvidenceProtocol(answer);
        boolean grounded = answer.contains("doc://") || answer.contains("web://");
        String normalized = answer.toLowerCase();
        boolean cautiousOrFailureLanguage = normalized.contains("\u672a\u80fd")
            || normalized.contains("\u672a\u627e\u5230")
            || normalized.contains("\u6ca1\u6709\u627e\u5230")
            || normalized.contains("\u672a\u68c0\u7d22\u5230")
            || normalized.contains("\u672a\u5339\u914d")
            || normalized.contains("\u65e0\u76f8\u5173")
            || normalized.contains("\u4fe1\u606f\u7f3a\u5931")
            || normalized.contains("\u8bc1\u636e\u4e0d\u8db3")
            || normalized.contains("\u65e0\u6cd5\u786e\u8ba4")
            || normalized.contains("not found")
            || normalized.contains("no relevant")
            || normalized.contains("no evidence")
            || normalized.contains("unable to find")
            || normalized.contains("insufficient evidence");
        boolean substantive = !cautiousOrFailureLanguage
            || grounded
            || hasSubstantiveAnalysisStructure(answer)
            || containsAny(normalized,
                "\u4f46\u786e\u8ba4", "\u4f46\u53ef\u4ee5\u786e\u8ba4", "\u4f46\u6587\u6863\u663e\u793a",
                "\u5df2\u786e\u8ba4", "\u53ef\u4ee5\u786e\u8ba4", "\u6587\u6863\u663e\u793a",
                "\u6587\u6863\u8bf4\u660e", "\u91cd\u70b9\u5305\u62ec", "\u4e3b\u8981\u5305\u62ec",
                "however, the evidence confirms", "but the document confirms");
        return new AnswerQualityAssessment(
            substantive,
            grounded,
            !internalProtocol,
            false,
            false,
            internalProtocol
        );
    }

    /**
     * A limitation statement inside an otherwise complete report is not a refusal. The signal is
     * deliberately domain-neutral: Runtime must not depend on domain terms, tool names, or
     * dataset identifiers when deciding whether a model produced a substantive business result.
     */
    private boolean hasSubstantiveAnalysisStructure(String answer) {
        if (answer == null) {
            return false;
        }
        String text = answer.trim();
        if (text.length() < 600) {
            return false;
        }
        int headings = 0;
        int evidenceItems = 0;
        int paragraphs = 0;
        boolean paragraphOpen = false;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                if (paragraphOpen) {
                    paragraphs++;
                    paragraphOpen = false;
                }
                continue;
            }
            paragraphOpen = true;
            if (line.matches("^#{1,6}\\s+.+")) {
                headings++;
            }
            if (line.matches("^(?:[-*+]\\s+|\\d+[.)]\\s+|\\|.+\\|$).+")) {
                evidenceItems++;
            }
        }
        if (paragraphOpen) {
            paragraphs++;
        }
        return headings >= 2 && evidenceItems >= 3
            || headings >= 3 && paragraphs >= 3
            || text.length() >= 1_200 && paragraphs >= 4;
    }

    private boolean containsInternalEvidenceProtocol(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String normalized = answer.toLowerCase();
        return answer.contains(DeterministicAnswerCompiler.LOCK_HEADER)
            || answer.contains(DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER)
            || answer.contains(DeterministicAnswerCompiler.END_LOCKED_ANSWER)
            || normalized.contains("based on the executed evidence graph path")
            || (normalized.contains("source references:")
                && normalized.contains("evidence facts:")
                && normalized.contains("execution constraint:"));
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null || value.isBlank() || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String groundedEvidenceAnswer(List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence) {
        StringBuilder answer = new StringBuilder();
        answer.append("\u6839\u636e\u5df2\u68c0\u7d22\u5230\u7684\u6587\u6863\uff0c\u53ef\u786e\u8ba4\u7684\u91cd\u70b9\u5185\u5bb9\u5982\u4e0b\uff1a\n\n");
        int limit = Math.min(5, evidence.size());
        for (int i = 0; i < limit; i++) {
            AnswerDecisionEngine.GroundedDocumentEvidence item = evidence.get(i);
            answer.append(i + 1).append(". ");
            if (item.source() != null && !item.source().isBlank()) {
                answer.append("**").append(item.source()).append("**\uff1a");
            }
            if (item.section() != null && !item.section().isBlank()) {
                answer.append("[").append(item.section()).append("] ");
            }
            answer.append(shortText(item.content(), 320));
            if (item.citation() != null && !item.citation().isBlank()) {
                answer.append(" ").append(item.citation());
            }
            answer.append("\n");
        }
        answer.append("\n\u4ee5\u4e0a\u5185\u5bb9\u5747\u6765\u81ea\u672c\u6b21\u68c0\u7d22\u8bc1\u636e\uff1b\u6587\u6863\u672a\u8986\u76d6\u7684\u7ec6\u8282\u4e0d\u4f5c\u63a8\u6d4b\u3002");
        return answer.toString();
    }

    private List<AnswerDecisionEngine.GroundedDocumentEvidence> extractGroundedDocumentEvidence(List<String> observations) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence = new ArrayList<>();
        for (String observation : observations) {
            evidence.addAll(extractGroundedDocumentEvidence(observation));
        }
        Map<String, AnswerDecisionEngine.GroundedDocumentEvidence> unique = new LinkedHashMap<>();
        for (AnswerDecisionEngine.GroundedDocumentEvidence item : evidence) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            unique.putIfAbsent(groundedEvidenceDedupKey(item), item);
            if (unique.size() >= 8) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private String groundedEvidenceDedupKey(AnswerDecisionEngine.GroundedDocumentEvidence item) {
        String documentId = documentIdFromCitation(item.citation());
        String source = firstNonBlank(item.source(), firstNonBlank(documentId, ""));
        String section = firstNonBlank(item.section(), "");
        String normalizedContent = item.content()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
        return source.trim().toLowerCase(Locale.ROOT)
            + "|" + section.trim().toLowerCase(Locale.ROOT)
            + "|" + normalizedContent;
    }

    private String documentIdFromCitation(String citation) {
        if (citation == null || !citation.startsWith("doc://")) {
            return null;
        }
        int fragment = citation.indexOf('#');
        return fragment > 6 ? citation.substring(6, fragment) : citation.substring(6);
    }

    private List<AnswerDecisionEngine.GroundedDocumentEvidence> extractGroundedDocumentEvidence(String observation) {
        if (observation == null || observation.isBlank()
            || (!observation.contains("doc://") && !observation.contains(DOCUMENT_EVIDENCE_CONTRACT)
            && !observation.contains(UNIFIED_EVIDENCE_CONTRACT))) {
            return List.of();
        }
        List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence = new ArrayList<>();
        GroundedDocumentEvidenceBuilder current = null;
        boolean capturingContent = false;
        for (String rawLine : observation.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("[Evidence ")) {
                addGroundedEvidence(evidence, current);
                current = new GroundedDocumentEvidenceBuilder();
                capturingContent = false;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("type:")) {
                current.type = line.substring("type:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("citation:")) {
                current.citation = line.substring("citation:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("source:")) {
                current.source = line.substring("source:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("section:")) {
                current.section = line.substring("section:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("content:")) {
                capturingContent = true;
            } else if (capturingContent) {
                if (isEvidenceContextBoundary(line)) {
                    capturingContent = false;
                } else if (!line.isBlank()) {
                    if (!current.content.isEmpty()) {
                        current.content.append(' ');
                    }
                    current.content.append(shortText(line, 420));
                }
            }
        }
        addGroundedEvidence(evidence, current);
        return evidence;
    }

    private void addGroundedEvidence(List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence,
                                     GroundedDocumentEvidenceBuilder current) {
        if (current == null) {
            return;
        }
        String type = current.type == null ? "" : current.type.trim();
        String citation = current.citation == null ? "" : current.citation.trim();
        if (!"DOCUMENT".equalsIgnoreCase(type) && !citation.startsWith("doc://")) {
            return;
        }
        String content = current.content.toString().trim();
        if (content.isBlank()) {
            return;
        }
        evidence.add(new AnswerDecisionEngine.GroundedDocumentEvidence(
            blankToNull(citation),
            blankToNull(current.source),
            blankToNull(current.section),
            content
        ));
    }

    private boolean isEvidenceContextBoundary(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return line.startsWith("[Evidence ")
            || line.startsWith("Evidence audit:")
            || line.startsWith("Document search summary:")
            || line.startsWith("Document evidence snippets:")
            || line.startsWith("Citation rule:")
            || line.startsWith(DeterministicAnswerCompiler.LOCK_HEADER)
            || line.startsWith(DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER)
            || line.startsWith(DeterministicAnswerCompiler.END_LOCKED_ANSWER);
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<Map<String, Object>> extractCitationMaps(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = DOCUMENT_REF_PATTERN.matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        while (matcher.find()) {
            String fileId = matcher.group(1);
            String chunkValue = matcher.group(2);
            String refId = "doc://" + fileId + "#chunk=" + chunkValue;
            if (!seen.add(refId)) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("refId", refId);
            citation.put("type", "DOCUMENT");
            citation.put("fileId", fileId);
            citation.put("fileName", null);
            citation.put("section", null);
            citation.put("chunkIndex", parseChunkIndex(chunkValue));
            citations.add(citation);
        }
        Matcher webMatcher = WEB_REF_PATTERN.matcher(text);
        while (webMatcher.find()) {
            String source = webMatcher.group(1);
            String resultValue = webMatcher.group(2);
            String refId = "web://" + source + "#result=" + resultValue;
            if (!seen.add(refId)) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("refId", refId);
            citation.put("type", "WEB");
            citation.put("source", source);
            citation.put("resultIndex", parseChunkIndex(resultValue));
            citations.add(citation);
        }
        return List.copyOf(citations);
    }

    private boolean hasUnknownCitation(List<Map<String, Object>> answerCitations,
                                       List<Map<String, Object>> availableCitations) {
        if (answerCitations.isEmpty() || availableCitations.isEmpty()) {
            return false;
        }
        Set<String> availableRefIds = new LinkedHashSet<>();
        for (Map<String, Object> citation : availableCitations) {
            Object refId = citation.get("refId");
            if (refId != null) {
                availableRefIds.add(String.valueOf(refId));
            }
        }
        return answerCitations.stream()
            .map(citation -> citation.get("refId"))
            .filter(value -> value != null)
            .map(String::valueOf)
            .anyMatch(refId -> !availableRefIds.contains(refId));
    }

    private Integer parseChunkIndex(String chunkValue) {
        if (chunkValue == null || chunkValue.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(chunkValue);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String finalAnswerText(String answer, List<String> missingInfo) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        if (missingInfo == null || missingInfo.isEmpty()) {
            return "";
        }
        return INSUFFICIENT_EVIDENCE_ANSWER;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private Map<String, Object> supportingDatasetSpec(Map<String, Object> source,
                                                      boolean defaultCollapsed) {
        Map<String, Object> spec = new LinkedHashMap<>(source);
        Map<String, Object> ui = new LinkedHashMap<>(objectMap(spec.get("ui")));
        ui.put("channel", "supporting_dataset");
        ui.put("role", "evidence_attachment");
        ui.put("primaryDisplay", false);
        ui.put("defaultCollapsed", defaultCollapsed);
        spec.put("ui", Map.copyOf(ui));
        spec.put("presentationChannel", "supporting_dataset");
        return Map.copyOf(spec);
    }

    private Object firstPresent(Object first, Object second) {
        return first == null ? second : first;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private record AnswerQualityAssessment(
        boolean hasSubstantiveConclusion,
        boolean groundedByEvidence,
        boolean addressesQuestion,
        boolean containsUnsupportedClaims,
        boolean trulyEmpty,
        boolean containsInternalProtocolMarker
    ) {
        private boolean requiresFallback() {
            return trulyEmpty
                || containsInternalProtocolMarker
                || !addressesQuestion
                || containsUnsupportedClaims
                || !hasSubstantiveConclusion;
        }

        private Map<String, Object> asMetadata() {
            return Map.of(
                "contractVersion", "answer_quality_assessment_v1",
                "hasSubstantiveConclusion", hasSubstantiveConclusion,
                "groundedByEvidence", groundedByEvidence,
                "addressesQuestion", addressesQuestion,
                "containsUnsupportedClaims", containsUnsupportedClaims,
                "trulyEmpty", trulyEmpty,
                "containsInternalProtocolMarker", containsInternalProtocolMarker,
                "requiresFallback", requiresFallback()
            );
        }
    }

    private static class GroundedDocumentEvidenceBuilder {
        private String type;
        private String citation;
        private String source;
        private String section;
        private final StringBuilder content = new StringBuilder();
    }
}
