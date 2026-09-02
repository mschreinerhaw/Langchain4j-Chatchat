package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.contract.AnalysisContextPresentationContract;
import com.chatchat.agents.orchestration.analysis.contract.SemanticInsightContractProvider;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.analysis.model.SemanticInsightContract;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLifecycle;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.runtime.summary.analysis.AnalysisLoopContract;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisSummaryProtocol;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisWorkerSupervision;
import dev.langchain4j.model.chat.ChatModel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Reconciles Worker outputs into complete, traceable synthesis inputs. */
public final class AnalysisCoverageCoordinator {

    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;
    private final AnalysisEvidenceCoordinator evidenceCoordinator;
    private final AnalysisDispatchCoordinator dispatchCoordinator;
    private final DeterministicInsightEngine insightEngine;
    private final AnalysisSynthesisCoordinator synthesisCoordinator;
    private final AnalysisReducerSupervisor reducerSupervisor = new AnalysisReducerSupervisor();
    private final AnalysisGovernanceStateCoordinator governanceStateCoordinator =
        new AnalysisGovernanceStateCoordinator();
    private final Configuration configuration;
    private AnalysisEvidenceSpillStore spillStore;

    public AnalysisCoverageCoordinator(
        AgentRunResultAdapter resultAdapter,
        String runIdAttribute,
        AnalysisEvidenceCoordinator evidenceCoordinator,
        AnalysisDispatchCoordinator dispatchCoordinator,
        DeterministicInsightEngine insightEngine,
        AnalysisSynthesisCoordinator synthesisCoordinator,
        AnalysisEvidenceSpillStore spillStore,
        Configuration configuration
    ) {
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
        this.evidenceCoordinator = evidenceCoordinator;
        this.dispatchCoordinator = dispatchCoordinator;
        this.insightEngine = insightEngine;
        this.synthesisCoordinator = synthesisCoordinator;
        this.spillStore = spillStore == null ? AnalysisEvidenceSpillStore.disabled() : spillStore;
        this.configuration = configuration;
    }

    public void setSpillStore(AnalysisEvidenceSpillStore store) {
        this.spillStore = store == null ? AnalysisEvidenceSpillStore.disabled() : store;
    }

    public CoverageBundle analyze(Request request) {
        AnalysisEvidenceCoordinator.Projection projection = evidenceCoordinator.project(
            request.result(), request.runtimeAttributes());
        List<AnalysisEvidenceCoordinator.Dataset> datasets = projection.datasets();
        writeExcludedMetadata(request.metadata(), projection.excludedDatasets());
        projection.excludedDatasets().forEach(excluded -> observe(request,
            "数据集未进入分析：" + excluded.get("datasetReference") + "（未返回非空结构化记录）。",
            "analysis_summary_governance", metadataOf(
                "type", "analysis_dataset_excluded", "exclusion", excluded)));
        if (datasets.isEmpty()) return CoverageBundle.empty();

        DatasetRelationshipPlan relationshipPlan = evidenceCoordinator.relationshipPlan(
            datasets, request.summaryProtocol());
        DataAnalysisLifecycle lifecycle = DataAnalysisLifecycle
            .begin(request.isolationScope().partitionKey() + ":record-analysis", datasets.size())
            .relationshipsEstablished(relationshipPlan.groups().size(), relationshipPlan.edges().size());
        observe(request, "已完成数据集关系分析，共形成 " + relationshipPlan.groups().size() + " 个分析组。",
            "analysis_summary_governance", metadataOf(
                "type", "dataset_relationship_plan", "relationshipPlan", relationshipPlan.toMap()));

        Object driverRepairs = request.metadata() == null ? null
            : request.metadata().get("analysisDriverRepairRequests");
        boolean resumedDriverRepair = request.metadata() != null
            && request.metadata().containsKey("analysisDriverRepairRound")
            && driverRepairs instanceof java.util.Collection<?> repairs && !repairs.isEmpty();
        AnalysisDispatchCoordinator.DispatchBatch dispatched = dispatchCoordinator.dispatch(
            dispatchRequest(request, datasets, resumedDriverRepair));
        if (resumedDriverRepair) {
            request.metadata().put("analysisDriverRepairDatasetReused", true);
            request.metadata().put("analysisDataRequeryAllowed", false);
        }
        lifecycle = lifecycle.datasetsDispatched(dispatched.taskCount());
        writeDispatchMetadata(request.metadata(), dispatched);
        if (dispatched.taskCount() > 0) {
            observe(request, "已启动 " + dispatched.taskCount() + " 个数据集分析任务，并行度为 "
                    + dispatched.workerCount() + "。", "analysis_summary_governance",
                metadataOf("type", "analysis_summary_dispatched", "taskCount", dispatched.taskCount(),
                    "workerCount", dispatched.workerCount(), "dispatchMode", dispatched.mode()));
        }
        CoverageBundle firstPass;
        try {
            firstPass = reconcile(request, datasets, relationshipPlan, lifecycle, dispatched);
        } finally {
            dispatched.close();
        }
        if (!automaticReanalysisRequired(request, firstPass)) return firstPass;

        request.metadata().put("analysisAutomaticReanalysisTriggered", true);
        request.metadata().put("analysisAutomaticReanalysisRound", 1);
        request.metadata().put("analysisReuseExistingDataset", true);
        request.metadata().put("analysisDataRequeryAllowed", false);
        observe(request,
            "已保留现有数据并从 Worker 分析阶段自动重试，无需重新获取数据。",
            "analysis_summary_governance", metadataOf(
                "type", "analysis_reanalysis_started", "round", 1,
                "resumeFrom", "WORKER_ANALYSIS", "reuseExistingDataset", true,
                "dataAcquisitionAllowed", false));
        AnalysisDispatchCoordinator.DispatchBatch retryBatch = dispatchCoordinator.dispatch(
            dispatchRequest(request, datasets, true));
        try {
            CoverageBundle retry = reconcile(request, datasets, relationshipPlan,
                lifecycle, retryBatch);
            request.metadata().put("analysisAutomaticReanalysisCompleted", true);
            request.metadata().put("analysisAutomaticReanalysisRecovered",
                !retry.synthesisInputs().isEmpty());
            return retry;
        } finally {
            retryBatch.close();
        }
    }

    private AnalysisDispatchCoordinator.DispatchRequest dispatchRequest(
        Request request,
        List<AnalysisEvidenceCoordinator.Dataset> datasets,
        boolean reanalysis
    ) {
        List<AnalysisDispatchCoordinator.DatasetInput> inputs = datasets.stream()
            .map(dataset -> new AnalysisDispatchCoordinator.DatasetInput(
                dataset.reference(), reanalysis
                    ? reanalysisContext(dataset.analysisContext(), request.metadata())
                    : dataset.analysisContext(), dataset.records()))
            .toList();
        return new AnalysisDispatchCoordinator.DispatchRequest(
            request.model(), request.query(),
            stringValue(request.metadata() == null ? null : request.metadata().get("modelName")),
            inputs, request.isolationScope(), request.runtimeAttributes(), request.cancellationCheck());
    }

    private Map<String, Object> reanalysisContext(Map<String, Object> source,
                                                   Map<String, Object> metadata) {
        Map<String, Object> context = new LinkedHashMap<>(source == null ? Map.of() : source);
        context.put("analysisRepair", Map.of(
            "round", 1,
            "resumeFrom", "WORKER_ANALYSIS",
            "reuseExistingDataset", true,
            "dataAcquisitionAllowed", false,
            "workerSupervision", metadata == null
                ? Map.of() : metadata.getOrDefault("analysisWorkerSupervision", Map.of()),
            "repairRequests", metadata == null
                ? List.of() : metadata.getOrDefault("analysisRepairRequests", List.of())));
        return Map.copyOf(context);
    }

    private boolean automaticReanalysisRequired(Request request, CoverageBundle firstPass) {
        if (request.metadata() == null || firstPass == null
            || firstPass.returnedRecordCount() == 0
            || !firstPass.synthesisInputs().isEmpty()) return false;
        if (Boolean.TRUE.equals(request.metadata().get("analysisAutomaticReanalysisTriggered"))) {
            return false;
        }
        return Boolean.FALSE.equals(request.metadata().get("analysisSynthesisBarrierReady"));
    }

    private CoverageBundle reconcile(
        Request request,
        List<AnalysisEvidenceCoordinator.Dataset> datasets,
        DatasetRelationshipPlan relationshipPlan,
        DataAnalysisLifecycle initialLifecycle,
        AnalysisDispatchCoordinator.DispatchBatch dispatched
    ) {
        StringBuilder prompt = new StringBuilder(
            "Returned-record evidence (record_grounded_analysis.v1). "
                + "Every successful range below is processed evidence; final analysis must use it, "
                + "must not substitute execution metadata, and must respect listed Worker failures.\n");
        StringBuilder appendix = new StringBuilder();
        StringBuilder rawReplay = new StringBuilder();
        List<List<String>> valueGroups = new ArrayList<>();
        List<AnalysisSummaryResult> governedSummaries = new ArrayList<>();
        List<AnalysisSummaryResult> datasetSummaries = new ArrayList<>();
        List<DeterministicInsightEngine.DatasetInput> insightDatasets = new ArrayList<>();
        List<Map<String, Object>> insightResults = new ArrayList<>();
        List<Map<String, Object>> insightDecisions = new ArrayList<>();
        List<Map<String, Object>> presentationViews = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<DataAnalysisWorkerSupervision.WorkerReport> workerReports = new ArrayList<>();
        AnalysisWorkerSupervisor workerSupervisor = new AnalysisWorkerSupervisor();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Counters counters = new Counters();

        int datasetIndex = 0;
        for (AnalysisEvidenceCoordinator.Dataset dataset : datasets) {
            datasetIndex++;
            request.cancellationGuard().run();
            int occurrence = occurrences.merge(dataset.reference(), 1, Integer::sum);
            String reference = occurrence == 1
                ? dataset.reference() : dataset.reference() + "#occurrence-" + occurrence;
            counters.returned += dataset.records().size();
            counters.sourceComplete &= dataset.records().stream()
                .noneMatch(record -> Boolean.FALSE.equals(record.get("sourceComplete")));
            AnalysisDispatchCoordinator.Outcome outcome = dispatched.await(reference);
            DataAnalysisWorkerSupervision.WorkerReport workerReport = workerSupervisor.inspect(
                reference, dataset.records().size(), outcome,
                evidenceCoordinator::hasTraceableEvidence);
            workerReports.add(workerReport);
            observeWorkerSupervision(request, workerReport, datasetIndex, datasets.size());
            if (!workerReport.acceptedForSynthesis()) {
                if (workerReport.productStatus()
                    == DataAnalysisWorkerSupervision.ProductStatus.EXECUTION_FAILED) {
                    recordFailure(request, prompt, appendix, failures, reference, datasetIndex,
                        datasets.size(), dataset.records().size(), outcome);
                } else {
                    recordRejectedWorkerProduct(request, prompt, appendix, failures, reference,
                        datasetIndex, datasets.size(), dataset.records().size(), outcome, workerReport);
                }
                continue;
            }
            if (!outcome.success()) {
                recordFailure(request, prompt, appendix, failures, reference, datasetIndex,
                    datasets.size(), dataset.records().size(), outcome);
                continue;
            }
            AnalysisDatasetSummary summary = outcome.summary();
            counters.analyzed++;
            request.isolationScope().requireSamePartition(summary.datasetSummary().isolationScope());
            datasetSummaries.add(summary.datasetSummary());
            counters.iterative |= summary.oversized();
            if (!summary.oversized()) {
                dataset.records().forEach(record ->
                    valueGroups.add(evidenceCoordinator.valueGroup(record, request.query())));
            }
            observe(request, "第 " + datasetIndex + "/" + datasets.size()
                    + " 组业务数据分析完成，正在汇总业务结果。", "business_analysis_progress",
                metadataOf("type", "business_analysis_result_ready", "stage", "BUSINESS_RESULT_READY",
                    "workReference", reference, "workIndex", datasetIndex,
                    "workCount", datasets.size(), "chunkCount", summary.chunks().size()));

            Map<String, Object> governedContext = request.summaryProtocol().govern(
                reference, dataset.analysisContext(), dataset.records());
            Map<String, Object> presentation =
                AnalysisContextPresentationContract.semanticView(reference, governedContext);
            presentationViews.add(presentation);
            prompt.append("- ").append(reference).append(" business semantic view: ")
                .append(ModelProtocolJson.compact(presentation)).append("\n");
            collectInsights(request, dataset, reference, governedContext, prompt,
                insightDatasets, insightResults, insightDecisions);
            appendChunks(request, dataset, reference, summary, appendix, rawReplay,
                governedSummaries, counters);
            observe(request, "数据集 " + datasetIndex + "/" + datasets.size() + " 分析完成，共 "
                    + summary.chunks().size() + " 个数据切片。", "analysis_summary_governance",
                metadataOf("type", "analysis_dataset_completed", "datasetReference", reference,
                    "datasetIndex", datasetIndex, "datasetCount", datasets.size(),
                    "chunkCount", summary.chunks().size(), "workerOwnedChunking", true,
                    "spilledChunkCount", summary.spilledChunkCount(),
                    "restoredCheckpointCount", summary.restoredCheckpointCount(),
                    "retriedChunkCount", summary.retriedChunkCount(),
                    "retryCount", summary.totalRetryCount(),
                    "datasetReductionAttemptCount", summary.datasetReductionAttemptCount(),
                    "datasetReductionRestoredCheckpoint", summary.datasetReductionRestoredCheckpoint(),
                    "datasetSummaryResultId", summary.datasetSummary().resultId(),
                    "summaryResultIds", summary.inputSummaryResultIds()));
            appendix.append("\n");
        }

        DataAnalysisWorkerSupervision.DriverReport supervision =
            new DataAnalysisWorkerSupervision().reconcile(datasets.size(), workerReports);
        writeSupervisionMetadata(request, supervision);
        DataAnalysisLifecycle lifecycle = initialLifecycle.workersReconciled(
            supervision.acceptedWorkerCount(), supervision.rejectedWorkerCount());
        if (!supervision.synthesisReady()) {
            prompt.append("Driver synthesis barrier is blocked: no Worker produced an admitted "
                + "business-analysis product. Raw returned records must not be summarized or published.\n");
            HierarchicalAnalysisReducer.Result hierarchy = new HierarchicalAnalysisReducer.Result(
                relationshipPlan, List.of(), List.of(), List.of(),
                datasets.stream().map(AnalysisEvidenceCoordinator.Dataset::reference).toList());
            boolean coverageComplete = false;
            boolean traceComplete = false;
            appendCoverage(prompt, hierarchy, failures, counters, coverageComplete, traceComplete);
            writeResultMetadata(request, datasets.size(), relationshipPlan, lifecycle, hierarchy,
                governedSummaries, failures, insightResults, insightDecisions, presentationViews,
                counters, coverageComplete, traceComplete);
            return new CoverageBundle(prompt.toString(), appendix.toString(), List.of(),
                counters.returned, 0, counters.iterations, counters.iterative,
                false, counters.sourceComplete, false, counters.rawReplay,
                List.of(), List.of());
        }
        AnalysisSynthesisCoordinator.HierarchicalSynthesisResult synthesis =
            synthesisCoordinator.synthesizeHierarchy(
                new AnalysisSynthesisCoordinator.HierarchicalSynthesisRequest(
                    request.model()::chat, request.isolationScope(), relationshipPlan, request.query(),
                    datasetSummaries, insightDatasets, lifecycle, request.runtimeAttributes()));
        lifecycle = synthesis.lifecycle();
        DeterministicInsightEngine.Result bundleInsights = synthesis.crossDatasetInsights();
        HierarchicalAnalysisReducer.Result hierarchy = synthesis.hierarchy();
        AnalysisReducerSupervisor.Review reducerReview =
            reducerSupervisor.inspect(hierarchy.finalInputs());
        AnalysisGovernanceStateCoordinator.State governanceState =
            governanceStateCoordinator.reconcile(reducerReview.admittedInputs(),
                reducerReview.repairRequests(), request.metadata());
        hierarchy = new HierarchicalAnalysisReducer.Result(
            hierarchy.relationshipPlan(), hierarchy.datasetSummaries(),
            hierarchy.relationshipGroupSummaries(), reducerReview.admittedInputs(),
            hierarchy.uncoveredDatasets());
        writeReducerGovernanceMetadata(request, reducerReview, governanceState);
        observe(request,
            reducerReview.rejectedCount() == 0
                ? "分析主管报告已通过治理准入，可进入综合决策。"
                : "部分分析主管报告未通过治理准入，已生成补证或重算请求。",
            "analysis_summary_governance", metadataOf(
                "type", "analysis_reducer_admission",
                "admittedCount", reducerReview.admittedInputs().size(),
                "rejectedCount", reducerReview.rejectedCount(),
                "repairRequestCount", reducerReview.repairRequests().size(),
                "activeRepairRequestCount", governanceState.activeRepairRequests().size(),
                "terminalRepairRequestCount", reducerReview.repairRequests().size()
                    - governanceState.activeRepairRequests().size(),
                "gapRequests", toGapRequests(governanceState.activeRepairRequests())));
        if (bundleInsights.executed()
            && (!bundleInsights.findings().isEmpty() || !bundleInsights.issues().isEmpty())) {
            insightResults.add(bundleInsights.toMap());
            prompt.append("Cross-dataset deterministic findings (authoritative calculations): ")
                .append(ModelProtocolJson.compact(bundleInsights.toMap())).append("\n");
        }
        boolean coverageComplete = counters.processed == counters.returned;
        boolean traceComplete = counters.processed > 0
            && governedSummaries.size() == counters.iterations
            && governedSummaries.stream().allMatch(evidenceCoordinator::hasTraceableEvidence)
            && governedSummaries.stream().map(AnalysisSummaryResult::resultId).distinct().count()
                == governedSummaries.size();
        if (hierarchy.finalInputs().isEmpty()) {
            traceComplete = false;
            prompt.append("Driver synthesis barrier is blocked: no Reducer report passed layered governance. "
                + "Use the generated analysis repair requests instead of reconstructing conclusions from raw evidence.\n");
        }
        appendCoverage(prompt, hierarchy, failures, counters, coverageComplete, traceComplete);
        if (!rawReplay.isEmpty()) {
            // Raw replay is retained for Worker repair and governance diagnostics, but never enters
            // the management-level Driver prompt. The Driver reviews admitted analysis products;
            // allowing it to reinterpret raw rows would collapse the Worker/Driver responsibility boundary.
            if (request.metadata() != null) {
                request.metadata().put("analysisRawReplayAvailableForWorkerRepair", true);
                request.metadata().put("analysisRawReplayWithheldFromDriver", true);
            }
            observe(request, "原始证据回放已保留用于分析修复和审计，不进入综合决策模型。",
                "analysis_summary_governance", metadataOf(
                    "type", "analysis_raw_replay_isolated",
                    "rawReplayChunkCount", counters.rawReplay,
                    "driverAccess", false));
        }
        writeResultMetadata(request, datasets.size(), relationshipPlan, lifecycle, hierarchy,
            governedSummaries, failures, insightResults, insightDecisions, presentationViews,
            counters, coverageComplete, traceComplete);
        return new CoverageBundle(prompt.toString(), appendix.toString(), List.copyOf(valueGroups),
            counters.returned, counters.processed, counters.iterations, counters.iterative,
            coverageComplete, counters.sourceComplete, traceComplete, counters.rawReplay,
            List.copyOf(governedSummaries), hierarchy.finalInputs());
    }

    private void writeReducerGovernanceMetadata(
        Request request,
        AnalysisReducerSupervisor.Review review,
        AnalysisGovernanceStateCoordinator.State governanceState
    ) {
        Map<String, Object> metadata = request.metadata();
        if (metadata == null) return;
        metadata.put("analysisReducerAdmissionDecisions", review.admissionDecisions());
        metadata.put("analysisReducerAdmittedReportCount", review.admittedInputs().size());
        metadata.put("analysisReducerRejectedReportCount", review.rejectedCount());
        metadata.put("analysisRepairRequests", review.repairRequests());
        metadata.put("analysisRepairRequired", !governanceState.activeRepairRequests().isEmpty());
        List<Map<String, Object>> gapRequests = toGapRequests(governanceState.activeRepairRequests());
        metadata.put("analysisGapRequests", gapRequests);
        metadata.put("gapRequests", gapRequests);
        metadata.put("analysisGapsAdvisoryOnly", true);
        metadata.put("analysisAdvisoryGapCount", gapRequests.size());
        if (review.admittedInputs().isEmpty()) {
            metadata.put("analysisSynthesisBarrierReady", false);
            metadata.put("analysisSynthesisBarrierStatus", "REDUCER_ADMISSION_BLOCKED");
        } else {
            metadata.put("analysisSynthesisBarrierReady", true);
            metadata.put("analysisSynthesisBarrierStatus",
                review.rejectedCount() == 0 ? "READY" : "READY_WITH_REDUCER_LIMITATIONS");
        }
    }

    private List<Map<String, Object>> toGapRequests(List<Map<String, Object>> repairs) {
        if (repairs == null || repairs.isEmpty()) return List.of();
        return repairs.stream().map(repair -> new AnalysisLoopContract.GapRequest(
                stringValue(repair.get("requestId")),
                stringValue(repair.get("goal")),
                stringList(repair.get("requiredCapabilities")),
                stringValue(repair.get("requiredTimeScope")),
                stringValue(repair.get("requiredGrain")),
                AnalysisLoopContract.Criticality.CORE,
                stringValue(repair.get("route")))
            .toMap()).toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item).trim());
            }
        });
        return result.stream().distinct().toList();
    }

    private void observeWorkerSupervision(
        Request request,
        DataAnalysisWorkerSupervision.WorkerReport report,
        int index,
        int count
    ) {
        boolean accepted = report.acceptedForSynthesis();
        String stage = report.fullyCompliant() ? "ANALYSIS_ACCEPTED"
            : accepted ? "ANALYSIS_DEGRADED" : "ANALYSIS_REJECTED";
        observe(request,
            "第 " + index + "/" + count + " 组数据分析"
                + (accepted ? "已通过质量验收。" : "未通过质量验收，不会进入综合结论。"),
            "business_analysis_progress", metadataOf(
                "type", "analysis_worker_supervision",
                "stage", stage,
                "workReference", report.datasetReference(),
                "workIndex", index,
                "workCount", count,
                "workerReport", report.toMap()));
    }

    private void recordRejectedWorkerProduct(
        Request request,
        StringBuilder prompt,
        StringBuilder appendix,
        List<Map<String, Object>> failures,
        String reference,
        int index,
        int count,
        int records,
        AnalysisDispatchCoordinator.Outcome outcome,
        DataAnalysisWorkerSupervision.WorkerReport report
    ) {
        Map<String, Object> failure = metadataOf(
            "workReference", reference, "workIndex", index, "workCount", count,
            "recordCount", records, "status", report.productStatus().name(),
            "durationMs", report.durationMs(), "error", String.join(",", report.reasons()),
            "workerReport", report.toMap());
        failures.add(failure);
        prompt.append("- ").append(reference)
            .append(" returned data but did not produce an admitted Worker analysis. "
                + "Do not use its raw payload as a conclusion. Supervision: ")
            .append(ModelProtocolJson.compact(report.toMap())).append("\n");
        appendix.append("### ").append(reference)
            .append("\n\n- 数据已返回，但分析质量验收未通过，本数据集未进入综合结论。\n\n");
    }

    private void writeSupervisionMetadata(
        Request request,
        DataAnalysisWorkerSupervision.DriverReport supervision
    ) {
        if (request.metadata() != null) {
            request.metadata().put("analysisWorkerSupervision", supervision.toMap());
            request.metadata().put("analysisWorkerSupervisionSchemaVersion",
                DataAnalysisWorkerSupervision.SCHEMA_VERSION);
            request.metadata().put("analysisSynthesisBarrierReady", supervision.synthesisReady());
            request.metadata().put("analysisSynthesisBarrierStatus",
                supervision.barrierStatus().name());
            request.metadata().put("analysisAcceptedWorkerCount",
                supervision.acceptedWorkerCount());
            request.metadata().put("analysisRejectedWorkerCount",
                supervision.rejectedWorkerCount());
        }
        observe(request,
            supervision.synthesisReady()
                ? "数据分析任务已完成对账，可以开始综合结论。"
                : "数据分析任务已完成对账，但没有通过质量验收的分析结果，暂不生成综合结论。",
            "business_analysis_progress", metadataOf(
                "type", "analysis_driver_barrier",
                "stage", supervision.synthesisReady()
                    ? "SYNTHESIS_READY" : "SYNTHESIS_BLOCKED",
                "workReference", supervision.expectedWorkerCount() == 1
                    ? supervision.workers().get(0).datasetReference() : "all-datasets",
                "workIndex", supervision.terminalWorkerCount(),
                "workCount", supervision.expectedWorkerCount(),
                "supervision", supervision.toMap()));
    }

    private void collectInsights(Request request, AnalysisEvidenceCoordinator.Dataset dataset,
        String reference, Map<String, Object> context, StringBuilder prompt,
        List<DeterministicInsightEngine.DatasetInput> inputs, List<Map<String, Object>> results,
        List<Map<String, Object>> decisions) {
        SemanticInsightContractProvider.Resolution resolution = evidenceCoordinator.resolveSemanticInsights(
            request.isolationScope(), reference, context, request.runtimeAttributes(), request.metadata());
        decisions.add(metadataOf("dataset", reference, "status", resolution.status(),
            "reason", resolution.reason(), "contractIds",
            resolution.contracts().stream().map(SemanticInsightContract::contractId).toList()));
        for (SemanticInsightContract contract : resolution.contracts()) {
            DeterministicInsightEngine.Result result = insightEngine.analyze(
                request.isolationScope(), reference, contract, dataset.records());
            inputs.add(new DeterministicInsightEngine.DatasetInput(reference, contract, dataset.records()));
            if (!result.executed()) continue;
            results.add(result.toMap());
            prompt.append("- ").append(reference)
                .append(" deterministic findings (authoritative calculations; the model may explain but must not recalculate or alter them): ")
                .append(ModelProtocolJson.compact(result.toMap())).append("\n");
            observe(request, "Deterministic semantic insights recorded for " + reference + ".",
                "deterministic_insights", metadataOf("type", "deterministic_insights",
                    "result", result.toMap()));
        }
    }

    private void appendChunks(Request request, AnalysisEvidenceCoordinator.Dataset dataset,
        String reference, AnalysisDatasetSummary summary, StringBuilder appendix,
        StringBuilder rawReplay, List<AnalysisSummaryResult> governedSummaries, Counters counters) {
        appendix.append("### ").append(reference).append("\n\n");
        counters.spilledChunks += summary.spilledChunkCount();
        counters.spilledBytes += summary.spilledByteCount();
        counters.restoredCheckpoints += summary.restoredCheckpointCount();
        if (summary.datasetReductionRestoredCheckpoint()) counters.restoredDatasetReductions++;
        counters.retriedChunks += summary.retriedChunkCount();
        counters.retryCount += summary.totalRetryCount();
        counters.iterations += summary.chunks().size();
        for (AnalysisDatasetSummary.ChunkResult chunkResult : summary.chunks()) {
            request.cancellationGuard().run();
            AnalysisSummaryResult governed = chunkResult.summary();
            Map<String, Object> position = governed.position();
            int from = intValue(position.get("recordFrom"), 1);
            int to = intValue(position.get("recordTo"), from);
            if (from < 1 || to < from || to > dataset.records().size()) {
                throw new IllegalStateException("Worker returned invalid record range for " + reference);
            }
            List<Map<String, Object>> chunk = dataset.records().subList(from - 1, to);
            governedSummaries.add(governed);
            if (request.metadata() != null && "STRUCTURED_RECORD_FALLBACK".equals(governed.outcome())) {
                request.metadata().put("recordAnalysisChunkFallback", true);
            }
            counters.processed += chunk.size();
            appendix.append("- records[").append(from).append("..").append(to).append("]：")
                .append(governed.content()).append("\n");
            if (!evidenceCoordinator.requiresRawReplay(governed)) continue;
            counters.rawReplay++;
            AnalysisEvidenceSpillStore.SpillReference spill = chunkResult.spillReference();
            String replay = spill == null ? ModelProtocolJson.compact(chunk)
                : new String(spillStore.read(request.isolationScope(), spill), StandardCharsets.UTF_8);
            rawReplay.append("- evidenceId=")
                .append(stringValue(governed.evidence().get("evidenceId")))
                .append(" position=").append(ModelProtocolJson.compact(position))
                .append(" contentSha256=")
                .append(stringValue(governed.evidence().get("contentSha256")))
                .append(" rawRecords=").append(replay).append("\n");
        }
    }

    private void recordFailure(Request request, StringBuilder prompt, StringBuilder appendix,
        List<Map<String, Object>> failures, String reference, int index, int count, int records,
        AnalysisDispatchCoordinator.Outcome outcome) {
        Map<String, Object> failure = metadataOf("workReference", reference, "workIndex", index,
            "workCount", count, "recordCount", records, "status", outcome.status(),
            "durationMs", outcome.durationMs(), "error", outcome.error());
        failures.add(failure);
        prompt.append("- ").append(reference)
            .append(" was not analyzed because dataset processing failed. Do not infer facts from this dataset. Failure: ")
            .append(ModelProtocolJson.compact(failure)).append("\n");
        appendix.append("### ").append(reference).append("\n\n- 分析未完成：")
            .append(outcome.status()).append("，")
            .append(firstNonBlank(outcome.error(), "未返回可用结果")).append("。\n\n");
        observe(request, "第 " + index + "/" + count + " 组业务数据处理未完成，将继续处理其他数据。",
            "business_analysis_progress", metadataOf("type", "business_analysis_partial_failure",
                "stage", "PARTIAL_DATA_UNAVAILABLE", "failure", failure));
    }

    private void appendCoverage(StringBuilder prompt, HierarchicalAnalysisReducer.Result hierarchy,
        List<Map<String, Object>> failures, Counters counters, boolean complete, boolean traceComplete) {
        prompt.append("Coverage: returnedRecordCount=").append(counters.returned)
            .append(", processedRecordCount=").append(counters.processed)
            .append(", complete=").append(complete)
            .append(", sourceContentComplete=").append(counters.sourceComplete)
            .append(", evidenceTraceComplete=").append(traceComplete)
            .append(", rawReplayChunkCount=").append(counters.rawReplay).append(".\n");
        if (!failures.isEmpty()) {
            prompt.append("Worker failure isolation: analyzedDatasetCount=").append(counters.analyzed)
                .append(", failedDatasetCount=").append(failures.size())
                .append(". Final conclusions must be limited to successful datasets. Failed datasets: ")
                .append(ModelProtocolJson.compact(failures)).append("\n");
        }
        prompt.append(hierarchy.promptEvidence());
        if (!hierarchy.uncoveredDatasets().isEmpty()) {
            prompt.append("Runtime relationship coverage recovery retained uncovered datasets as standalone final inputs: ")
                .append(ModelProtocolJson.compact(hierarchy.uncoveredDatasets())).append("\n");
        }
    }

    private void writeExcludedMetadata(Map<String, Object> metadata,
                                       List<Map<String, Object>> excluded) {
        if (metadata == null) return;
        metadata.put("recordAnalysisExcludedDatasets", excluded);
        metadata.put("recordAnalysisExcludedDatasetCount", excluded.size());
    }

    private void writeDispatchMetadata(Map<String, Object> metadata,
                                       AnalysisDispatchCoordinator.DispatchBatch batch) {
        if (metadata == null) return;
        metadata.put("recordAnalysisSummaryParallel", batch.isParallel());
        metadata.put("recordAnalysisSummaryScheduledTaskCount", batch.taskCount());
        metadata.put("recordAnalysisSummaryWorkerCount", batch.workerCount());
        metadata.put("recordAnalysisSummaryDispatchMode", batch.mode());
        metadata.put("recordAnalysisSummaryWorkerHeartbeatIntervalMs", configuration.heartbeatIntervalMs());
        metadata.put("recordAnalysisSummaryWorkerHeartbeatTimeoutMs", configuration.heartbeatTimeoutMs());
        metadata.put("recordAnalysisWorkerModelTimeoutPolicy", "SYSTEM_MODEL_REQUEST_TIMEOUT");
        metadata.put("recordAnalysisSummaryWorkerMaxRetries", configuration.maximumRetries());
        metadata.put("recordAnalysisSummaryWorkerMaxAttempts", configuration.maximumRetries() + 1);
    }

    private void writeResultMetadata(Request request, int datasetCount,
        DatasetRelationshipPlan relationships, DataAnalysisLifecycle lifecycle,
        HierarchicalAnalysisReducer.Result hierarchy, List<AnalysisSummaryResult> summaries,
        List<Map<String, Object>> failures, List<Map<String, Object>> insightResults,
        List<Map<String, Object>> insightDecisions, List<Map<String, Object>> presentations,
        Counters counters, boolean complete, boolean traceComplete) {
        Map<String, Object> metadata = request.metadata();
        if (metadata == null) return;
        metadata.put("recordAnalysisContractVersion", "record_grounded_analysis.v1");
        metadata.put("analysisDecisionOperatingModelVersion",
            DataAnalysisDecisionOperatingModel.SCHEMA_VERSION);
        metadata.put("analysisGovernanceParticipantRole",
            DataAnalysisDecisionOperatingModel.ParticipantRole.GOVERNANCE.name());
        metadata.put("recordAnalysisReturnedRecordCount", counters.returned);
        metadata.put("recordAnalysisProcessedRecordCount", counters.processed);
        metadata.put("recordAnalysisCoverageComplete", complete);
        metadata.put("recordAnalysisDatasetCount", datasetCount);
        metadata.put("recordAnalysisSuccessfulDatasetCount", counters.analyzed);
        metadata.put("recordAnalysisFailedDatasetCount", failures.size());
        metadata.put("recordAnalysisFailedDatasets", List.copyOf(failures));
        metadata.put("recordAnalysisPartialWorkerFailure", counters.analyzed > 0 && !failures.isEmpty());
        metadata.put("recordAnalysisAllWorkersFailed", counters.analyzed == 0 && !failures.isEmpty());
        metadata.put("recordAnalysisEvidenceTraceComplete", traceComplete);
        metadata.put("recordAnalysisSourceContentComplete", counters.sourceComplete);
        metadata.put("recordAnalysisIterationCount", counters.iterations);
        metadata.put("recordAnalysisIterative", counters.iterative);
        metadata.put("recordAnalysisRawReplayChunkCount", counters.rawReplay);
        metadata.put("recordAnalysisSpilledChunkCount", counters.spilledChunks);
        metadata.put("recordAnalysisSpilledByteCount", counters.spilledBytes);
        metadata.put("recordAnalysisRestoredCheckpointCount", counters.restoredCheckpoints);
        metadata.put("recordAnalysisRestoredDatasetReductionCount", counters.restoredDatasetReductions);
        metadata.put("recordAnalysisRetriedChunkCount", counters.retriedChunks);
        metadata.put("recordAnalysisRetryCount", counters.retryCount);
        metadata.put("analysisSummaryGovernanceBridge", request.summaryProtocol().ledger(
            summaries, counters.returned, counters.processed, complete));
        metadata.put("datasetRelationshipPlan", relationships.toMap());
        metadata.put("datasetRelationshipGroupCount", relationships.groups().size());
        metadata.put("datasetRelationshipEdgeCount", relationships.edges().size());
        metadata.put("datasetRelationshipUnresolvedReferences", relationships.unresolvedReferences());
        metadata.put("hierarchicalDatasetSummaryCount", hierarchy.datasetSummaries().size());
        metadata.put("hierarchicalRelationshipGroupSummaryCount", hierarchy.relationshipGroupSummaries().size());
        metadata.put("hierarchicalFinalInputCount", hierarchy.finalInputs().size());
        metadata.put("hierarchicalUncoveredDatasets", hierarchy.uncoveredDatasets());
        metadata.put("dataAnalysisLifecycle", lifecycle.toMap());
        metadata.put("hierarchicalAnalysisReduce", metadataOf(
            "schemaVersion", HierarchicalAnalysisReducer.SCHEMA_VERSION,
            "relationshipPlan", relationships.toMap(),
            "datasetSummaries", hierarchy.datasetSummaries().stream().map(AnalysisSummaryResult::toMap).toList(),
            "relationshipGroupSummaries", hierarchy.relationshipGroupSummaries().stream()
                .map(AnalysisSummaryResult::toMap).toList(),
            "finalInputSummaryResultIds", hierarchy.finalInputs().stream()
                .map(AnalysisSummaryResult::resultId).toList(),
            "uncoveredDatasets", hierarchy.uncoveredDatasets()));
        metadata.put("deterministicInsightContractVersion", DeterministicInsightEngine.RESULT_VERSION);
        metadata.put("deterministicInsightResults", List.copyOf(insightResults));
        metadata.put("deterministicInsightApplicability", List.copyOf(insightDecisions));
        metadata.put("analysisContextPresentationVersion", AnalysisContextPresentationContract.VERSION);
        metadata.put("analysisContextPresentationViews", List.copyOf(presentations));
        metadata.put("deterministicInsightFindingCount", insightResults.stream().mapToInt(item -> {
            Object findings = item.get("findings");
            return findings instanceof List<?> list ? list.size() : 0;
        }).sum());
    }

    private void observe(Request request, String content, String source, Map<String, Object> metadata) {
        Map<String, Object> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        values.put("tenantId", request.isolationScope().tenantId());
        values.put("runId", request.isolationScope().runId());
        resultAdapter.recordRuntimeObservation(
            request.runtimeAttributes(), runIdAttribute, content, source, Map.copyOf(values));
    }

    public record Configuration(int maximumRetries, long heartbeatIntervalMs,
                                long heartbeatTimeoutMs) {}

    public record Request(ChatModel model, String query,
        InterpretationPlanRuntime.ExecutionResult result, Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata, BooleanSupplier cancellationCheck,
        Runnable cancellationGuard, GovernanceIsolationScope isolationScope,
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> summaryProtocol) {
        public Request {
            runtimeAttributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
            cancellationCheck = cancellationCheck == null ? () -> false : cancellationCheck;
            cancellationGuard = cancellationGuard == null ? () -> {} : cancellationGuard;
        }
    }

    public record CoverageBundle(String promptEvidence, String appendix,
        List<List<String>> recordValueGroups, int returnedRecordCount, int processedRecordCount,
        int iterations, boolean iterative, boolean coverageComplete, boolean sourceContentComplete,
        boolean evidenceTraceComplete, int rawReplayChunkCount,
        List<AnalysisSummaryResult> summaryResults, List<AnalysisSummaryResult> synthesisInputs) {
        public CoverageBundle {
            recordValueGroups = recordValueGroups == null ? List.of() : List.copyOf(recordValueGroups);
            summaryResults = summaryResults == null ? List.of() : List.copyOf(summaryResults);
            synthesisInputs = synthesisInputs == null ? List.of() : List.copyOf(synthesisInputs);
        }
        public static CoverageBundle empty() {
            return new CoverageBundle("", "", List.of(), 0, 0, 0, false,
                true, true, true, 0, List.of(), List.of());
        }
    }

    private static final class Counters {
        private int returned;
        private int processed;
        private int iterations;
        private int analyzed;
        private int rawReplay;
        private int spilledChunks;
        private int restoredCheckpoints;
        private int restoredDatasetReductions;
        private int retriedChunks;
        private int retryCount;
        private long spilledBytes;
        private boolean iterative;
        private boolean sourceComplete = true;
    }
}
