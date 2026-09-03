package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisExecutionOutcome;
import com.chatchat.agents.orchestration.analysis.model.AnalysisReportContract;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLifecycle;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLayerGovernanceContract;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLineageGraph;
import com.chatchat.common.runtime.summary.spi.ModelSummaryModel;
import com.chatchat.common.runtime.summary.spi.ModelSummaryReducer;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Coordinates dataset, cross-dataset and final governed synthesis without domain knowledge.
 */
public final class AnalysisSynthesisCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisSynthesisCoordinator.class);

    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;
    private final AnalysisSummaryGovernanceCoordinator governanceCoordinator;
    private final DeterministicInsightEngine deterministicInsightEngine;
    private final AnswerCandidateCollector answerCandidateCollector;
    private final GovernedFinalClaimContract finalClaimContract = new GovernedFinalClaimContract();
    private final AnalysisDriverPipelineContext driverPipelineContext =
        new AnalysisDriverPipelineContext();
    private ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
        HierarchicalAnalysisReducer.Result> hierarchicalReducer;

    public AnalysisSynthesisCoordinator(
        AgentRunResultAdapter resultAdapter,
        String runIdAttribute,
        AnalysisSummaryGovernanceCoordinator governanceCoordinator,
        DeterministicInsightEngine deterministicInsightEngine,
        AnswerCandidateCollector answerCandidateCollector,
        ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
            HierarchicalAnalysisReducer.Result> hierarchicalReducer
    ) {
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
        this.governanceCoordinator = governanceCoordinator;
        this.deterministicInsightEngine = deterministicInsightEngine;
        this.answerCandidateCollector = answerCandidateCollector;
        this.hierarchicalReducer = hierarchicalReducer;
    }

    public void setHierarchicalReducer(
        ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
            HierarchicalAnalysisReducer.Result> reducer
    ) {
        if (reducer != null) this.hierarchicalReducer = reducer;
    }

    public HierarchicalSynthesisResult synthesizeHierarchy(HierarchicalSynthesisRequest request) {
        DeterministicInsightEngine.Result crossDataset = deterministicInsightEngine.analyzeBundle(
            request.isolationScope(), request.deterministicDatasets());
        HierarchicalAnalysisReducer.Result hierarchy = hierarchicalReducer.reduce(
            new HierarchicalAnalysisReducer.Context(
                request.model(), request.isolationScope(), request.relationshipPlan(), request.userQuestion()),
            request.workerDatasetSummaries());
        DataAnalysisLifecycle lifecycle = request.lifecycle()
            .finalSummaryCompleted(hierarchy.finalInputs().size());

        for (AnalysisSummaryResult datasetSummary : hierarchy.datasetSummaries()) {
            record(request, "数据集归并分析完成："
                + datasetSummary.position().get("datasetReference") + "。", "dataset_synthesis",
                Map.of("analysisSummaryResult", datasetSummary.toMap()));
        }
        for (AnalysisSummaryResult groupSummary : hierarchy.relationshipGroupSummaries()) {
            record(request, "关系组归并分析完成："
                + groupSummary.position().get("groupId") + "。", "relationship_group_synthesis",
                Map.of("analysisSummaryResult", groupSummary.toMap()));
        }
        return new HierarchicalSynthesisResult(crossDataset, hierarchy, lifecycle);
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

    /** Executes the single final model call, its deterministic fallback and final governance. */
    public FinalSynthesisResult synthesizeFinal(FinalModelSynthesisRequest request) {
        if (Boolean.FALSE.equals(request.metadata().get("analysisSynthesisBarrierReady"))) {
            recordHumanReviewAdvisory(request, "SYNTHESIS_INPUT_REVIEW",
                String.valueOf(request.metadata().getOrDefault(
                    "analysisSynthesisBarrierStatus", "ANALYSIS_PRODUCTS_REQUIRE_REVIEW")));
            request.metadata().put("analysisSynthesisBarrierWasAdvisory", true);
            log.warn("analysisSynthesisReviewAdvisory runId={} stage={} status={} "
                    + "acceptedWorkerCount={} rejectedWorkerCount={}",
                request.runId(), request.stage(),
                request.metadata().get("analysisSynthesisBarrierStatus"),
                request.metadata().get("analysisAcceptedWorkerCount"),
                request.metadata().get("analysisRejectedWorkerCount"));
        }
        long startedAt = System.currentTimeMillis();
        // The Driver consumes the final Worker/Reducer reports. Chunk-level products remain
        // lineage and audit evidence, but cannot bypass hierarchical consolidation.
        List<AnalysisSummaryResult> claimSources = request.synthesisInputs().isEmpty()
            ? new ArrayList<>(request.summaryResults())
            : new ArrayList<>(request.synthesisInputs());
        request.metadata().put("analysisDecisionOperatingModelVersion",
            DataAnalysisDecisionOperatingModel.SCHEMA_VERSION);
        request.metadata().put("analysisParticipantRole",
            DataAnalysisDecisionOperatingModel.ParticipantRole.DRIVER.name());
        request.metadata().put("analysisDriverInputMode", request.synthesisInputs().isEmpty()
            ? DataAnalysisDecisionOperatingModel.DriverInputMode.WORKER_REPORT_COMPATIBILITY_FALLBACK.name()
            : DataAnalysisDecisionOperatingModel.DriverInputMode.GOVERNED_WORKER_REDUCER_REPORTS_ONLY.name());
        request.metadata().put("analysisDriverInputReportCount", claimSources.size());
        GovernedFinalClaimContract.Compilation claimCompilation =
            finalClaimContract.compile(claimSources);
        boolean synthesisBarrierReady =
            Boolean.TRUE.equals(request.metadata().get("analysisSynthesisBarrierReady"));
        boolean claimBoundPublication = claimCompilation.active() && synthesisBarrierReady;
        request.metadata().put("finalClaimPublicationContractVersion",
            GovernedFinalClaimContract.SCHEMA_VERSION);
        request.metadata().put("finalClaimPublicationContractObserved",
            claimCompilation.claimContractObserved());
        request.metadata().put("finalClaimPublicationContractActive", claimBoundPublication);
        request.metadata().put("finalAdmittedClaimCount", claimCompilation.claims().size());
        if (claimCompilation.claimContractObserved() && !claimCompilation.active()) {
            request.metadata().put("analysisHumanReviewRequired", true);
            request.metadata().put("analysisClaimReviewStatus",
                "NO_ADMITTED_CLAIMS_ADVISORY");
        }
        Map<String, Object> pipelineContext = driverPipelineContext.build(
            request.summaryResults(), claimSources, request.runtimeAttributes(), request.metadata());
        request.metadata().put("analysisDriverPipelineContext", pipelineContext);
        request.metadata().put("analysisDriverPipelineContextSchemaVersion",
            AnalysisDriverPipelineContext.SCHEMA_VERSION);
        String driverPrompt = request.prompt()
            + "\n\nBinding Driver role pipeline context (not evidence): "
            + ModelProtocolJson.compact(pipelineContext);
        String modelPrompt = claimBoundPublication
            ? finalClaimContract.appendSelectionInstruction(driverPrompt, claimCompilation)
            : driverPrompt;
        request.metadata().put("analysisDriverModelInvoked", true);
        request.metadata().put("analysisDriverModelPromptChars", modelPrompt.length());
        log.info("agentModelRequest phase=interpretation_plan_summary runId={} stage={} modelClass={} promptChars={} stepCount={} storedObservationCount={} claimBoundPublication={} admittedClaimCount={}",
            request.runId(), request.stage(), request.model().getClass().getName(),
            modelPrompt.length(), request.stepCount(), request.storedObservationCount(),
            claimBoundPublication, claimCompilation.claims().size());
        log.info("analysisDriverModelRequest runId={} stage={} modelClass={} promptChars={} "
                + "claimBoundPublication={} admittedClaimCount={}",
            request.runId(), request.stage(), request.model().getClass().getName(),
            modelPrompt.length(), claimBoundPublication, claimCompilation.claims().size());
        String answer;
        String outcome = "MODEL_FINAL_SUMMARY";
        GovernedFinalClaimContract.DriverAudit driverAudit = null;
        try {
            answer = request.model().chat(modelPrompt);
            if (claimBoundPublication) {
                driverAudit = finalClaimContract.inspectDriverAudit(answer, claimCompilation,
                    claimSources.stream().map(AnalysisSummaryResult::resultId).toList());
            }
        } catch (RuntimeException ex) {
            if (ex instanceof AgentDeadlineExceededException) throw ex;
            log.warn("agentModelFailure phase=interpretation_plan_summary runId={} stage={} "
                    + "fallbackAllowed={} errorType={} error={}",
                request.runId(), request.stage(), request.fallbackAllowed(),
                ex.getClass().getName(), safeMessage(ex));
            request.metadata().put("interpretationPlanSummaryGenerated", false);
            request.metadata().put("interpretationPlanSummaryFailure", safeMessage(ex));
            if (claimBoundPublication) {
                GovernedFinalClaimContract.Projection projection =
                    finalClaimContract.project("", claimCompilation);
                answer = projection.markdown();
                outcome = "DETERMINISTIC_CLAIM_BOUND_FINAL_SUMMARY";
                request.metadata().put("finalClaimSelectionAccepted", false);
                request.metadata().put("finalClaimSelectionReason",
                    "FINAL_MODEL_FAILED_" + projection.reason());
                request.metadata().put("finalPublishedClaimIds", projection.selectedClaimIds());
                request.metadata().put("interpretationPlanDeterministicClaimFallback", true);
            } else {
                if (!request.fallbackAllowed()) {
                    request.metadata().putIfAbsent("executionStatus", "NO_PRESENTABLE_RESULT");
                    return new FinalSynthesisResult("", null, false);
                }
                outcome = "DETERMINISTIC_FINAL_FALLBACK";
                request.metadata().put("interpretationPlanDeterministicSummaryFallback", true);
                // Publish the fallback marker before constructing the fallback. Presentation governance
                // must know that the candidate is not a model-authored global synthesis, otherwise a
                // long serialized tool envelope can be misclassified as narrative analysis.
                answer = request.fallbackSupplier().get();
                request.metadata().putIfAbsent("executionStatus", "PARTIAL_RESULT_PRESENTED");
            }
        }

        if (claimBoundPublication) {
            recordDriverAudit(request, driverAudit);
            if (driverAudit == null || !driverAudit.valid()) {
                String reason = driverAudit == null
                    ? "DRIVER_REVIEW_NOT_COMPLETED" : driverAudit.reason();
                recordHumanReviewAdvisory(request, "DRIVER_REVIEW", reason);
            } else if (!driverAudit.challenges().isEmpty()) {
                recordDriverChallenges(request, driverAudit);
            }
            if (driverAudit != null && driverAudit.valid()) {
                recordDriverDerivedClaims(request, driverAudit);
            }
        }

        if (!"DETERMINISTIC_FINAL_FALLBACK".equals(outcome)) {
            if (claimBoundPublication) {
                GovernedFinalClaimContract.Projection projection =
                    finalClaimContract.project(answer, claimCompilation);
                if (!projection.modelSelectionAccepted()) {
                    recordHumanReviewAdvisory(request, "DRIVER_DECISION", projection.reason());
                }
                answer = projection.markdown();
                outcome = projection.modelSelectionAccepted()
                    ? "CLAIM_BOUND_FINAL_SUMMARY"
                    : "DETERMINISTIC_CLAIM_SUMMARY_WITH_REVIEW_NOTES";
                request.metadata().put("finalClaimSelectionAccepted",
                    projection.modelSelectionAccepted());
                request.metadata().put("finalClaimSelectionReason", projection.reason());
                request.metadata().put("finalPublishedClaimIds", projection.selectedClaimIds());
                log.info("analysisDriverGovernance runId={} stage={} admitted={} reason={} "
                        + "publishedClaimCount={}",
                    request.runId(), request.stage(), projection.modelSelectionAccepted(),
                    projection.reason(), projection.selectedClaimIds().size());
            } else {
                answer = request.postProcessor().apply(answer);
            }
            if (answer == null || answer.isBlank()) {
                answer = request.emptyModelFallback();
                outcome = "MODEL_EMPTY_RUNTIME_FINAL_FALLBACK";
            }
        }
        AnalysisOutputAdmissionPolicy.Admission admission =
            AnalysisOutputAdmissionPolicy.admit(answer);
        request.metadata().put("analysisOutputAdmissionReason", admission.reason());
        request.metadata().put("analysisOutputAdmitted", admission.admitted());
        if (!admission.admitted()) {
            AnalysisExecutionOutcome executionOutcome = blockedOutcome(
                request, admission.reason(), true);
            answer = executionOutcome.failureReport();
            outcome = "ANALYSIS_OUTPUT_WITHHELD";
            request.metadata().put("executionStatus", "NO_PRESENTABLE_ANALYSIS");
            request.metadata().put("rawAnalysisOutputWithheld", true);
        } else {
            recordCompletedOutcome(request);
        }
        AnalysisSummaryResult governed = finalizeSummary(request.governance(answer, outcome));
        List<String> publishedClaimIds = strings(request.metadata().get("finalPublishedClaimIds"));
        DataAnalysisLayerGovernanceContract.Admission driverAdmission =
            new DataAnalysisLayerGovernanceContract.Admission(
                "", governed.resultId(), DataAnalysisLayerGovernanceContract.Layer.DRIVER_DECISION,
                admission.admitted() ? DataAnalysisLayerGovernanceContract.State.PUBLISHED
                    : DataAnalysisLayerGovernanceContract.State.REJECTED,
                admission.admitted(), List.of(admission.reason()),
                request.synthesisInputs().stream().map(AnalysisSummaryResult::resultId).toList(),
                publishedClaimIds);
        List<DataAnalysisLayerGovernanceContract.LineageEdge> driverLineageEdges = new ArrayList<>();
        String governedResultId = governed.resultId();
        request.synthesisInputs().forEach(input -> driverLineageEdges.add(
            new DataAnalysisLayerGovernanceContract.LineageEdge(
                "", input.resultId(), governedResultId,
                DataAnalysisLayerGovernanceContract.Relation.DERIVED_FROM,
                DataAnalysisLayerGovernanceContract.Layer.DRIVER_DECISION)));
        publishedClaimIds.forEach(claimId -> driverLineageEdges.add(
            new DataAnalysisLayerGovernanceContract.LineageEdge(
                "", claimId, governedResultId,
                DataAnalysisLayerGovernanceContract.Relation.SUPPORTS,
                DataAnalysisLayerGovernanceContract.Layer.DRIVER_DECISION)));
        List<Map<String, Object>> driverLineage = driverLineageEdges.stream()
            .map(DataAnalysisLayerGovernanceContract.LineageEdge::toMap).toList();
        List<Map<String, Object>> publishedClaimLifecycle = publishedClaimIds.stream()
            .map(claimId -> new DataAnalysisLayerGovernanceContract.ClaimTransition(
                "", claimId, DataAnalysisLayerGovernanceContract.Layer.DRIVER_DECISION,
                DataAnalysisLayerGovernanceContract.State.SYNTHESIZED,
                DataAnalysisLayerGovernanceContract.State.PUBLISHED,
                request.synthesisInputs().stream().map(AnalysisSummaryResult::resultId).toList(),
                "Claim was selected by the governed Driver publication contract.").toMap())
            .toList();
        governed = governed.withEvidence(Map.of(
            "analysisReportAdmission", driverAdmission.toMap(),
            "analysisEvidenceLineage", List.copyOf(driverLineage),
            "analysisClaimLifecycle", publishedClaimLifecycle,
            "analysisPublishedClaimIds", publishedClaimIds));
        request.metadata().put("analysisDriverAdmission", driverAdmission.toMap());
        request.metadata().put("analysisEvidenceLineage", List.copyOf(driverLineage));
        request.metadata().put("analysisClaimLifecycle", publishedClaimLifecycle);
        List<DataAnalysisLineageGraph.Node> driverNodes = new ArrayList<>();
        driverNodes.add(new DataAnalysisLineageGraph.Node(
            governedResultId, "DRIVER_DECISION",
            Map.of("outcome", governed.outcome(), "admitted", admission.admitted())));
        request.synthesisInputs().forEach(input -> driverNodes.add(
            new DataAnalysisLineageGraph.Node(input.resultId(), "REPORT",
                Map.of("scope", input.scope()))));
        publishedClaimIds.forEach(claimId -> driverNodes.add(
            new DataAnalysisLineageGraph.Node(claimId, "CLAIM", Map.of("published", true))));
        DataAnalysisLineageGraph lineageGraph = DataAnalysisLineageGraph
            .fromMap(request.metadata().get("analysisLineageGraph"))
            .plus(driverNodes, driverLineageEdges);
        request.metadata().put("analysisLineageGraph", lineageGraph.toMap());
        request.metadata().put("analysisLineageGraphSchemaVersion",
            DataAnalysisLineageGraph.SCHEMA_VERSION);
        request.metadata().put("analysisSummaryResult", governed.toMap());
        answer = governed.content();
        if (admission.admitted()) {
            if (claimBoundPublication) {
                recordFinalReportContract(request, answer);
            } else {
                AnalysisReportContract reportContract = AnalysisReportContract.driverReport(
                    answer, 0, 0, 1);
                request.metadata().put("analysisReportContract", reportContract.toMap());
                request.metadata().put("analysisReportContractSchemaVersion",
                    AnalysisReportContract.SCHEMA_VERSION);
                request.metadata().put("finalPayloadType", reportContract.reportType().name());
            }
        }
        log.info("agentModelResponse phase=interpretation_plan_summary runId={} stage={} durationMs={} responseChars={}",
            request.runId(), request.stage(), System.currentTimeMillis() - startedAt,
            answer == null ? 0 : answer.length());
        log.info("analysisDriverReport runId={} stage={} report={}",
            request.runId(), request.stage(), ModelProtocolJson.compact(
                AnalysisReportLogProjection.project(
                    "DRIVER", governed, request.synthesisInputs().size())));
        answerCandidateCollector.register(request.metadata(), AnswerCandidateCollector.FINAL_SYNTHESIS, answer);
        request.metadata().put("interpretationPlanSummaryGenerated",
            !"DETERMINISTIC_FINAL_FALLBACK".equals(outcome)
                && !"ANALYSIS_OUTPUT_WITHHELD".equals(outcome));
        request.metadata().put("interpretationPlanFinalResultProduced",
            !"ANALYSIS_OUTPUT_WITHHELD".equals(outcome));
        request.metadata().put("interpretationPlanSummaryStage", request.stage());
        request.metadata().put("interpretationPlanAttemptCount", request.attemptCount());
        request.metadata().put("interpretationPlanStoredObservationCount", request.storedObservationCount());
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("type", "final_summary");
        observation.put("workflow", "interpretation_plan");
        observation.put("stage", request.stage());
        observation.put("answerPreview", preview(answer));
        observation.put("analysisSummaryResult", governed.toMap());
        observation.put("tenantId", governed.isolationScope().tenantId());
        observation.put("runId", governed.isolationScope().runId());
        resultAdapter.recordRuntimeObservation(request.runtimeAttributes(), runIdAttribute,
            "InterpretationPlan " + request.stage() + " final stepwise summary generated.",
            "interpretation_plan_summary", observation);
        return new FinalSynthesisResult(answer, governed,
            !"ANALYSIS_OUTPUT_WITHHELD".equals(outcome));
    }

    /** Applies the governed Worker narrative and lossless coverage fallback before publication. */
    public String presentGovernedAnalysis(String answer, PresentationRequest request) {
        if (request.returnedRecordCount() == 0) {
            AnalysisOutputAdmissionPolicy.Admission admission =
                AnalysisOutputAdmissionPolicy.admit(answer);
            if (admission.admitted()) return answer;
            recordWithheld(request, admission.reason());
            return AnalysisOutputAdmissionPolicy.WITHHELD_MESSAGE;
        }
        String governedAnswer = governedNarrative(answer, request);
        if (Boolean.TRUE.equals(request.metadata().get("rawAnalysisOutputWithheld"))) {
            // A fail-closed decision must be terminal for presentation. Appending the raw coverage
            // appendix here would recreate the exact publication leak that the admission gate
            // rejected above.
            return governedAnswer;
        }
        boolean everyRecordReferenced = !request.iterative()
            && request.recordValueGroups().stream()
                .allMatch(values -> containsAnyConcreteValue(governedAnswer, values));
        if (everyRecordReferenced && !request.iterative() && request.sourceContentComplete()) {
            return governedAnswer;
        }
        long governedSummaryCount = request.summaryResults().stream()
            .filter(summary -> "MODEL_SUMMARY".equals(summary.outcome()))
            .filter(summary -> summary.content() != null && !summary.content().isBlank())
            .count();
        if (request.coverageComplete() && request.evidenceTraceComplete()
            && request.sourceContentComplete() && governedSummaryCount > 0) {
            request.metadata().put("recordAnalysisCoverageAppendixApplied", false);
            request.metadata().put("recordAnalysisNarrativeCoverageApplied", true);
            request.metadata().put("recordAnalysisEveryRecordReferencedByModel", everyRecordReferenced);
            request.metadata().put("recordAnalysisGovernedSummaryCount", governedSummaryCount);
            return governedAnswer;
        }
        request.metadata().put("recordAnalysisCoverageAppendixApplied", false);
        request.metadata().put("recordAnalysisDataFallbackApplied", true);
        request.metadata().put("recordAnalysisEveryRecordReferencedByModel", everyRecordReferenced);
        String limitation = !request.coverageComplete()
            ? "\n\n> 限制：部分已返回数据未完成分析，当前结论仅基于已处理数据。"
            : !request.sourceContentComplete()
                ? "\n\n> 限制：以上结果仅基于已返回的预览数据，不能代表完整源数据。"
                : "";
        return firstNonBlank(governedAnswer, "") + "\n\n## 已返回数据\n\n"
            + request.appendix() + limitation;
    }

    private String governedNarrative(String answer, PresentationRequest request) {
        if (GovernedGlobalSynthesisPolicy.retain(answer, request.coverageComplete(),
            request.evidenceTraceComplete(), request.metadata())) return answer.trim();
        List<AnalysisSummaryResult> preferred = request.synthesisInputs().isEmpty()
            ? request.summaryResults() : request.synthesisInputs();
        Set<String> modelSummaryIds = request.summaryResults().stream()
            .filter(summary -> "MODEL_SUMMARY".equals(summary.outcome()))
            .map(AnalysisSummaryResult::resultId).collect(java.util.stream.Collectors.toSet());
        List<AnalysisSummaryResult> modelSummaries = preferred.stream()
            .filter(summary -> summary.outcome().startsWith("MODEL_")
                || "MODEL_SUMMARY".equals(summary.outcome())
                || summary.inputSummaryResultIds().stream().anyMatch(modelSummaryIds::contains))
            .filter(summary -> summary.content() != null && !summary.content().isBlank())
            .collect(java.util.stream.Collectors.toMap(
                AnalysisSummaryResult::resultId, java.util.function.Function.identity(),
                (first, ignored) -> first, LinkedHashMap::new))
            .values().stream().toList();
        if (modelSummaries.isEmpty()) {
            request.metadata().put("governedNarrativeAnalysisUnavailable", true);
            request.metadata().put("returnedDataAnalysisRequired", true);
            AnalysisOutputAdmissionPolicy.Admission admission =
                AnalysisOutputAdmissionPolicy.admit(answer);
            boolean modelNarrative = admission.admitted()
                && !Boolean.TRUE.equals(request.metadata()
                    .get("interpretationPlanDeterministicSummaryFallback"));
            if (modelNarrative) {
                // Worker lineage is preferred, but a final model can still produce a legitimate
                // narrative for ordinary document/tool evidence. The accident boundary is raw
                // protocol publication, not the absence of a particular implementation lineage.
                request.metadata().put("ungovernedCandidateWithheld", false);
                request.metadata().put("analysisOutputAdmissionReason", admission.reason());
                request.metadata().put("analysisOutputAdmitted", true);
                return answer.trim();
            }
            request.metadata().put("ungovernedCandidateWithheld", true);
            request.metadata().put("analysisOutputAdmissionReason",
                admission.admitted()
                    ? "DETERMINISTIC_OUTPUT_WITHOUT_GOVERNED_ANALYSIS" : admission.reason());
            request.metadata().put("analysisOutputAdmitted", false);
            request.metadata().put("rawAnalysisOutputWithheld", true);
            return AnalysisOutputAdmissionPolicy.WITHHELD_MESSAGE;
        }
        StringBuilder narrative = new StringBuilder("\n\n## 数据分析总结\n\n");
        for (AnalysisSummaryResult summary : modelSummaries) {
            String dataset = text(summary.position().get("datasetReference"));
            String displayName = text(map(summary.analysisContext().get("source")).get("displayName"));
            if (modelSummaries.size() > 1 || (dataset != null && !dataset.isBlank())) {
                narrative.append("### ").append(firstNonBlank(displayName,
                    firstNonBlank(dataset, "数据集分析"))).append("\n\n");
            }
            narrative.append(summary.content().trim()).append("\n\n");
        }
        request.metadata().put("governedNarrativeAnalysisAppended", true);
        request.metadata().put("governedNarrativeAnalysisReplacedOperationalDraft", true);
        request.metadata().put("returnedDataAnalysisRequired", true);
        request.metadata().put("ungovernedCandidateWithheld", true);
        request.metadata().put("governedNarrativeAnalysisSummaryCount", modelSummaries.size());
        request.metadata().put("governedNarrativeAnalysisSource",
            request.synthesisInputs().isEmpty()
                ? "CHUNK_COMPATIBILITY_FALLBACK" : "DRIVER_SYNTHESIS_INPUTS");
        return narrative.toString().trim();
    }

    private void recordWithheld(PresentationRequest request, String reason) {
        request.metadata().put("analysisOutputAdmissionReason", reason);
        request.metadata().put("analysisOutputAdmitted", false);
        request.metadata().put("rawAnalysisOutputWithheld", true);
        request.metadata().put("returnedDataAnalysisRequired", true);
        request.metadata().put("supportingDatasetPrimaryDisplayAllowed", false);
        request.metadata().put("supportingDatasetDefaultCollapsed", true);
        request.metadata().put("executionStatus", "NO_PRESENTABLE_ANALYSIS");
    }

    private void recordDriverAudit(FinalModelSynthesisRequest request,
                                   GovernedFinalClaimContract.DriverAudit audit) {
        Map<String, Object> value = audit == null
            ? GovernedFinalClaimContract.DriverAudit.invalid(
                "DRIVER_REVIEW_NOT_COMPLETED").toMap()
            : audit.toMap();
        request.metadata().put("analysisDriverReview", value);
        request.metadata().put("analysisDriverReviewStatus",
            audit == null ? "INVALID" : audit.status());
        request.metadata().put("analysisDriverReviewCompleted", audit != null && audit.valid());
        request.metadata().put("analysisDriverDecisionStages", Map.of(
            "DRIVER_REVIEW", audit != null && audit.valid() ? "COMPLETED" : "REVIEW_REQUIRED",
            "DRIVER_REASONING", audit != null && audit.valid() ? "COMPLETED" : "PRESERVED",
            "DRIVER_DECISION", audit != null && audit.valid()
                ? (audit.challenges().isEmpty() ? "READY" : "READY_WITH_REVIEW_NOTES")
                : "READY_WITH_REVIEW_NOTES"));
        log.info("analysisDriverReview runId={} status={} valid={} challengeCount={} "
                + "derivedClaimCount={} reason={}", request.runId(),
            audit == null ? "INVALID" : audit.status(), audit != null && audit.valid(),
            audit == null ? 0 : audit.challenges().size(),
            audit == null ? 0 : audit.derivedClaims().size(),
            audit == null ? "DRIVER_REVIEW_NOT_COMPLETED" : audit.reason());
    }

    private void recordHumanReviewAdvisory(FinalModelSynthesisRequest request,
                                           String stage, String reason) {
        List<Map<String, Object>> notes = new ArrayList<>(
            maps(request.metadata().get("analysisHumanReviewNotes")));
        notes.add(Map.of(
            "stage", stage == null ? "ANALYSIS" : stage,
            "reason", reason == null ? "REVIEW_RECOMMENDED" : reason,
            "effect", "ADVISORY_ONLY"));
        request.metadata().put("analysisHumanReviewRequired", true);
        request.metadata().put("analysisHumanReviewNotes", List.copyOf(notes));
        request.metadata().put("analysisRepairRequired", false);
        request.metadata().remove("analysisDriverRepairRequests");
        request.metadata().put("analysisDriverDecisionAction", "PUBLISH_WITH_REVIEW_NOTES");
        request.metadata().put("analysisSynthesisBarrierReady", true);
        request.metadata().put("analysisSynthesisBarrierStatus", "READY_WITH_HUMAN_REVIEW_NOTES");
    }

    private void recordDriverChallenges(FinalModelSynthesisRequest request,
                                        GovernedFinalClaimContract.DriverAudit audit) {
        List<Map<String, Object>> challenges = audit.challenges().stream()
            .map(GovernedFinalClaimContract.DriverChallenge::toMap).toList();
        List<Map<String, Object>> repairs = new ArrayList<>();
        for (GovernedFinalClaimContract.DriverChallenge challenge : audit.challenges()) {
            boolean worker = "WORKER_REPORT".equals(challenge.targetLayer());
            DataAnalysisLayerGovernanceContract.Layer target = worker
                ? DataAnalysisLayerGovernanceContract.Layer.WORKER_REPORT
                : DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT;
            DataAnalysisLayerGovernanceContract.RepairRoute route = worker
                ? DataAnalysisLayerGovernanceContract.RepairRoute.REANALYZE_WORKER
                : DataAnalysisLayerGovernanceContract.RepairRoute.RERUN_REDUCER;
            DataAnalysisLayerGovernanceContract.RepairRequest repair =
                new DataAnalysisLayerGovernanceContract.RepairRequest(
                    "", challenge.targetReportId(), target, route,
                    challenge.requiredCorrection(), List.of(challenge.reason()),
                    List.of("GOVERNED_REPORT_REANALYSIS"), List.of(), "", "", target);
            Map<String, Object> repairMap = new LinkedHashMap<>(repair.toMap());
            repairMap.put("challengedClaimIds", challenge.claimIds());
            repairMap.put("issuedBy", "DRIVER_REVIEW");
            repairs.add(Map.copyOf(repairMap));
        }
        request.metadata().put("analysisDriverChallenges", challenges);
        request.metadata().put("analysisDriverSuggestedRepairRequests", List.copyOf(repairs));
        request.metadata().remove("analysisDriverRepairRequests");
        request.metadata().put("analysisRepairRequired", false);
        request.metadata().put("analysisHumanReviewRequired", true);
        request.metadata().put("analysisHumanReviewReasons", challenges);
        request.metadata().put("analysisDriverDecisionAction", "CHALLENGE_RECORDED_FOR_HUMAN_REVIEW");
        request.metadata().put("analysisSynthesisBarrierReady", true);
        request.metadata().put("analysisSynthesisBarrierStatus", "READY_WITH_DRIVER_REVIEW_NOTES");
    }

    private void recordDriverDerivedClaims(FinalModelSynthesisRequest request,
                                           GovernedFinalClaimContract.DriverAudit audit) {
        request.metadata().put("analysisDriverDecisionAction",
            audit.challenges().isEmpty() ? "APPROVE" : "APPROVE_WITH_REVIEW_NOTES");
        if (request.metadata().containsKey("analysisDriverRepairRound")) {
            request.metadata().put("analysisDriverRepairCompleted", true);
            request.metadata().put("analysisDriverRepairBudgetRemaining", 0);
            request.metadata().put("analysisRepairRequired", false);
            request.metadata().put("analysisDriverChallenges", List.of());
        }
        List<Map<String, Object>> derived = audit.derivedClaims().stream()
            .map(GovernedFinalClaimContract.DerivedClaim::toMap).toList();
        request.metadata().put("analysisDriverDerivedClaims", derived);
        if (audit.derivedClaims().isEmpty()) return;
        List<DataAnalysisLineageGraph.Node> nodes = audit.derivedClaims().stream()
            .map(claim -> new DataAnalysisLineageGraph.Node(
                claim.derivedClaimId(), "DRIVER_DERIVED_CLAIM",
                Map.of("caveats", claim.caveats()))).toList();
        List<DataAnalysisLayerGovernanceContract.LineageEdge> edges = new ArrayList<>();
        for (GovernedFinalClaimContract.DerivedClaim claim : audit.derivedClaims()) {
            claim.basisClaimIds().forEach(basis -> edges.add(
                new DataAnalysisLayerGovernanceContract.LineageEdge(
                    "", basis, claim.derivedClaimId(),
                    DataAnalysisLayerGovernanceContract.Relation.DERIVED_FROM,
                    DataAnalysisLayerGovernanceContract.Layer.DRIVER_DECISION)));
        }
        DataAnalysisLineageGraph graph = DataAnalysisLineageGraph
            .fromMap(request.metadata().get("analysisLineageGraph"))
            .plus(nodes, edges);
        request.metadata().put("analysisLineageGraph", graph.toMap());
        request.metadata().put("analysisLineageGraphSchemaVersion",
            DataAnalysisLineageGraph.SCHEMA_VERSION);
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
            unresolvedGaps(request.metadata(), request.synthesisInputs()),
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

    private void recordFinalReportContract(FinalModelSynthesisRequest request, String answer) {
        List<String> publishedClaimIds = strings(request.metadata().get("finalPublishedClaimIds"));
        Set<String> published = Set.copyOf(publishedClaimIds);
        Set<String> facts = new java.util.LinkedHashSet<>();
        Set<String> insights = new java.util.LinkedHashSet<>();
        List<AnalysisSummaryResult> reports = request.synthesisInputs().isEmpty()
            ? request.summaryResults() : request.synthesisInputs();
        for (AnalysisSummaryResult report : reports) {
            if (report == null || report.evidence() == null) continue;
            for (Map<String, Object> fact : maps(report.evidence().get("observedFactClaims"))) {
                String claimId = text(fact.get("claimId"));
                if (published.contains(claimId)) facts.add(claimId);
            }
            for (Map<String, Object> insight : maps(report.evidence().get("insights"))) {
                String claimId = text(insight.get("claimId"));
                if (!published.contains(claimId)) continue;
                if ("OBSERVED_RETURNED_FACT".equals(text(insight.get("claimClass")))) {
                    facts.add(claimId);
                } else {
                    insights.add(claimId);
                }
            }
        }
        int unclassifiedConclusions = Math.max(0,
            published.size() - facts.size() - insights.size());
        AnalysisReportContract reportContract = AnalysisReportContract.driverReport(
            answer, facts.size(), insights.size(), unclassifiedConclusions);
        request.metadata().put("analysisReportContract", reportContract.toMap());
        request.metadata().put("analysisReportContractSchemaVersion",
            AnalysisReportContract.SCHEMA_VERSION);
        request.metadata().put("finalPayloadType", reportContract.reportType().name());
    }

    private AnalysisExecutionOutcome blockedOutcome(FinalModelSynthesisRequest request,
                                                     String reason,
                                                     boolean governanceReached) {
        List<Map<String, Object>> gaps = unresolvedGaps(request.metadata(), request.synthesisInputs());
        boolean dataAvailable = request.returnedRecordCount() > 0;
        boolean workersAccepted = number(request.metadata().get("analysisAcceptedWorkerCount")) > 0
            || !request.summaryResults().isEmpty();
        boolean reducersAccepted = !request.synthesisInputs().isEmpty();
        AnalysisExecutionOutcome.ExecutionStatus status;
        AnalysisExecutionOutcome.FailureCategory category;
        AnalysisExecutionOutcome.RetryDirective retry;
        boolean driverReviewFailure = reason != null && reason.startsWith("DRIVER_REVIEW");
        boolean driverDecisionFailure = reason != null && reason.startsWith("DRIVER_DECISION");
        boolean driverChallengeFailure = "DRIVER_CHALLENGE_REQUIRES_REPAIR".equals(reason);
        boolean workerChallenge = maps(request.metadata().get("analysisDriverChallenges"))
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
        request.metadata().put("analysisExecutionOutcome", executionOutcome.toMap());
        request.metadata().put("analysisExecutionOutcomeSchemaVersion",
            AnalysisExecutionOutcome.SCHEMA_VERSION);
        request.metadata().put("analysisExecutionStatus", executionOutcome.status().name());
        request.metadata().put("analysisFailureCategory", executionOutcome.failureCategory().name());
        request.metadata().put("analysisRetryDirective", executionOutcome.retryDirective().toMap());
        request.metadata().put("analysisReuseExistingDataset", retry.reuseExistingDataset());
        request.metadata().put("analysisDataRequeryAllowed", retry.dataAcquisitionAllowed());
        request.metadata().put("returnedDataAnalysisRequired", dataAvailable);
        request.metadata().put("rawAnalysisOutputWithheld", dataAvailable);
        request.metadata().put("supportingDatasetChannel", "supporting_dataset");
        request.metadata().put("supportingDatasetPrimaryDisplayAllowed", false);
        request.metadata().put("supportingDatasetDefaultCollapsed", true);
        AnalysisReportContract reportContract = AnalysisReportContract.failureReport(
            executionOutcome.failureReport());
        request.metadata().put("analysisReportContract", reportContract.toMap());
        request.metadata().put("analysisReportContractSchemaVersion",
            AnalysisReportContract.SCHEMA_VERSION);
        request.metadata().put("finalPayloadType", reportContract.reportType().name());
        return executionOutcome;
    }

    private List<Map<String, Object>> unresolvedGaps(
        Map<String, Object> metadata,
        List<AnalysisSummaryResult> reports
    ) {
        LinkedHashMap<String, Map<String, Object>> gaps = new LinkedHashMap<>();
        for (String key : List.of("analysisGapRequests", "semanticClaimGapRequests", "gapRequests")) {
            for (Map<String, Object> gap : maps(metadata == null ? null : metadata.get(key))) {
                gaps.putIfAbsent(gapKey(gap), gap);
            }
        }
        if (reports != null) {
            for (AnalysisSummaryResult report : reports) {
                if (report == null || report.evidence() == null) continue;
                for (String key : List.of("semanticGapRequests", "analysisDepthGapRequests")) {
                    for (Map<String, Object> gap : maps(report.evidence().get(key))) {
                        gaps.putIfAbsent(gapKey(gap), gap);
                    }
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

    private boolean containsAnyConcreteValue(String answer, List<String> values) {
        String normalized = firstNonBlank(answer, "").replace(",", "");
        return values.stream().map(value -> value.replace(",", "")).anyMatch(normalized::contains);
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value); }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private String preview(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item).trim());
            }
        });
        return result.stream().distinct().toList();
    }

    private void record(HierarchicalSynthesisRequest request, String message, String type,
                        Map<String, Object> details) {
        Map<String, Object> metadata = new LinkedHashMap<>(details);
        metadata.put("type", type);
        metadata.put("tenantId", request.isolationScope().tenantId());
        metadata.put("runId", request.isolationScope().runId());
        resultAdapter.recordRuntimeObservation(request.runtimeAttributes(), runIdAttribute,
            message, "analysis_summary_governance", metadata);
    }

    public record HierarchicalSynthesisRequest(
        ModelSummaryModel model,
        GovernanceIsolationScope isolationScope,
        DatasetRelationshipPlan relationshipPlan,
        String userQuestion,
        List<AnalysisSummaryResult> workerDatasetSummaries,
        List<DeterministicInsightEngine.DatasetInput> deterministicDatasets,
        DataAnalysisLifecycle lifecycle,
        Map<String, Object> runtimeAttributes
    ) {
        public HierarchicalSynthesisRequest {
            workerDatasetSummaries = workerDatasetSummaries == null
                ? List.of() : List.copyOf(workerDatasetSummaries);
            deterministicDatasets = deterministicDatasets == null
                ? List.of() : List.copyOf(deterministicDatasets);
            runtimeAttributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
        }
    }

    public record HierarchicalSynthesisResult(
        DeterministicInsightEngine.Result crossDatasetInsights,
        HierarchicalAnalysisReducer.Result hierarchy,
        DataAnalysisLifecycle lifecycle
    ) {}

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
        boolean fallbackAllowed,
        Supplier<String> fallbackSupplier,
        UnaryOperator<String> postProcessor,
        String emptyModelFallback,
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
            fallbackSupplier = fallbackSupplier == null ? () -> "" : fallbackSupplier;
            postProcessor = postProcessor == null ? UnaryOperator.identity() : postProcessor;
            emptyModelFallback = emptyModelFallback == null ? "" : emptyModelFallback;
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
