package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.contract.AnalysisContextPresentationContract;
import com.chatchat.agents.orchestration.analysis.contract.SemanticInsightContractProvider;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.orchestration.analysis.dataset.DatasetReferenceSequence;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator;
import com.chatchat.agents.orchestration.analysis.execution.DatasetExecutionRegistry;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.nodes.synthesis.FinalSynthesisNode;
import com.chatchat.agents.orchestration.analysis.nodes.merge.StructuredFindingMerger;
import com.chatchat.agents.orchestration.analysis.nodes.analysis.WorkerPreprocessingCollector;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.analysis.model.SemanticInsightContract;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.runtime.summary.analysis.governance.DataAnalysisLifecycle;
import com.chatchat.common.runtime.summary.analysis.contract.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.common.runtime.summary.analysis.governance.DataAnalysisWorkerSupervision;
import dev.langchain4j.model.chat.ChatModel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Collects Worker preprocessing outputs and organizes them for Driver synthesis. */
public final class AnalysisCoverageCoordinator {

    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;
    private final AnalysisEvidenceCoordinator evidenceCoordinator;
    private final DeterministicInsightEngine insightEngine;
    private final FinalSynthesisNode synthesisCoordinator;
    private final AnalysisDispatchCoordinator dispatchCoordinator;
    private final Configuration configuration;
    private AnalysisEvidenceSpillStore spillStore;
    private com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider profiles =
        com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider.empty();

    public void setDomainAnalysisProfileProvider(com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider provider) {
        this.profiles = provider == null ? com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider.empty() : provider;
    }

    public AnalysisCoverageCoordinator(
        AgentRunResultAdapter resultAdapter,
        String runIdAttribute,
        AnalysisEvidenceCoordinator evidenceCoordinator,
        DeterministicInsightEngine insightEngine,
        FinalSynthesisNode synthesisCoordinator,
        AnalysisEvidenceSpillStore spillStore,
        AnalysisDispatchCoordinator dispatchCoordinator,
        Configuration configuration
    ) {
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
        this.evidenceCoordinator = evidenceCoordinator;
        this.insightEngine = insightEngine;
        this.synthesisCoordinator = synthesisCoordinator;
        this.dispatchCoordinator = dispatchCoordinator;
        this.spillStore = spillStore == null ? AnalysisEvidenceSpillStore.disabled() : spillStore;
        this.configuration = configuration;
    }

    /** Compatibility constructor for tests and embedders that only use the unified fast path. */
    public AnalysisCoverageCoordinator(
        AgentRunResultAdapter resultAdapter, String runIdAttribute,
        AnalysisEvidenceCoordinator evidenceCoordinator, DeterministicInsightEngine insightEngine,
        FinalSynthesisNode synthesisCoordinator, AnalysisEvidenceSpillStore spillStore,
        Configuration configuration
    ) {
        this(resultAdapter, runIdAttribute, evidenceCoordinator, insightEngine,
            synthesisCoordinator, spillStore, null, configuration);
    }

    public void setSpillStore(AnalysisEvidenceSpillStore store) {
        this.spillStore = store == null ? AnalysisEvidenceSpillStore.disabled() : store;
    }

    public CoverageBundle analyze(Request request) {
        request.metadata().put("analysisDatasetProjectionAttempted", true);
        AnalysisEvidenceCoordinator.Projection projection = evidenceCoordinator.project(
            request.result(), request.runtimeAttributes());
        request.metadata().put("analysisDatasetProjectionCompleted", true);
        List<AnalysisEvidenceCoordinator.Dataset> datasets = externalizeLargeDatasets(
            projection.datasets(), request);
        request.metadata().put("analysisEvidenceSnapshotFingerprint",
            ModelProtocolJson.sha256Hex(datasets.stream().map(dataset -> Map.of(
                "reference", dataset.reference(),
                "contentSha256", dataset.handle().contentSha256())).toList()));
        request.metadata().put("analysisObservedReturnedRecordCount", datasets.stream().mapToLong(AnalysisEvidenceCoordinator.Dataset::recordCount).sum());
        writeExcludedMetadata(request.metadata(), projection.excludedDatasets());
        projection.excludedDatasets().forEach(excluded -> observe(request,
            "数据集未进入分析：" + excluded.get("datasetReference") + "（未返回非空结构化记录）。",
            "analysis_summary_governance", metadataOf(
                "type", "analysis_dataset_excluded", "exclusion", excluded)));
        if (datasets.isEmpty()) {
            writeEmptyProjectionCompletion(request, projection.excludedDatasets());
            boolean sourceFailed = projection.excludedDatasets().stream().anyMatch(
                item -> "FAILED".equals(item.get("accountingStatus")));
            return new CoverageBundle("", "", List.of(), 0, 0, 0, false,
                !sourceFailed, true, !sourceFailed, 0, List.of(), List.of());
        }

        DatasetExecutionRegistry datasetRegistry = new DatasetExecutionRegistry();
        DatasetReferenceSequence registryReferences = datasetReferences(datasets);
        for (AnalysisEvidenceCoordinator.Dataset dataset : datasets) {
            String reference = registryReferences.next(dataset.reference());
            datasetRegistry.expect(reference, request.isolationScope().runId(),
                integerValue(dataset.analysisContext().get("sourceStepId")),
                textValue(dataset.analysisContext().get("sourceName")), dataset.handle().contentSha256());
        }
        request.metadata().put("datasetExecutionRegistry", datasetRegistry.snapshotMap());

        DatasetRelationshipPlan relationshipPlan = evidenceCoordinator.relationshipPlan(
            datasets, request.summaryProtocol());
        DataAnalysisLifecycle lifecycle = DataAnalysisLifecycle
            .begin(request.isolationScope().partitionKey() + ":record-analysis", datasets.size())
            .relationshipsEstablished(relationshipPlan.groups().size(), relationshipPlan.edges().size());
        observe(request, "已完成数据集关系分析，共形成 " + relationshipPlan.groups().size() + " 个分析组。",
            "analysis_summary_governance", metadataOf(
                "type", "dataset_relationship_plan", "relationshipPlan", relationshipPlan.toMap()));

        Map<String, PreparedCalculation> prepared = new LinkedHashMap<>();
        DatasetReferenceSequence calculationReferences = datasetReferences(datasets);
        List<String> availableDatasets = datasets.stream().map(AnalysisEvidenceCoordinator.Dataset::reference).toList();
        java.util.function.Supplier<List<AnalysisEvidenceCoordinator.Dataset>> computation = () -> {
            List<AnalysisEvidenceCoordinator.Dataset> preparedDatasets = new ArrayList<>();
            for (var dataset : datasets) {
                request.cancellationGuard().run();
                String reference = calculationReferences.next(dataset.reference());
                datasetRegistry.analyzing(reference);
                request.metadata().put("datasetExecutionRegistry", datasetRegistry.snapshotMap());
                var inputs = new ArrayList<DeterministicInsightEngine.DatasetInput>();
                var results = new ArrayList<Map<String, Object>>();
                var decisions = new ArrayList<Map<String, Object>>();
                var calculationPrompt = new StringBuilder();
                Map<String, Object> context = new LinkedHashMap<>(dataset.analysisContext());
                collectInsights(request, dataset, reference, request.summaryProtocol().govern(
                    reference, context, dataset.records()), calculationPrompt, inputs, results, decisions);
                prepared.put(reference, new PreparedCalculation(inputs, results, decisions, calculationPrompt.toString()));
                context.put("runtimeAnalysisInputs", Map.of("availableDatasetReferences", availableDatasets,
                    "verifiedCalculations", results, "calculationDecisions", decisions,
                    "instruction", "Interpret verified calculations; do not recalculate. Other listed datasets are available to the coordinator, not missing external evidence. Do not infer joins."));
                preparedDatasets.add(new AnalysisEvidenceCoordinator.Dataset(dataset.reference(), context, dataset.handle()));
            }
            return List.copyOf(preparedDatasets);
        };
        DatasetAnalysisMode mode = selectMode(datasets);
        request.metadata().put("datasetAnalysisMode", mode.name());
        request.metadata().put("recordAnalysisSummaryDispatchMode", mode.name());
        if (mode == DatasetAnalysisMode.UNIFIED_QUESTION) {
            request.metadata().put("recordAnalysisSummaryParallel", false);
            request.metadata().put("recordAnalysisSummaryScheduledTaskCount", 1);
            request.metadata().put("recordAnalysisSummaryWorkerCount", 0);
        }
        String analysisGraphId = request.isolationScope().runId() + ":dataset-analysis";
        observe(request, mode == DatasetAnalysisMode.PER_DATASET_WORKERS
                ? "已按数据集启动独立分析 Worker，共 " + datasets.size() + " 个任务。"
                : "已启动小数据集统一分析快路径，共 " + datasets.size() + " 个数据集。",
            "analysis_graph", metadataOf("type", "dataset_analysis_started",
                "eventKind", "ANALYSIS_GRAPH", "eventState", "STARTED", "graphId", analysisGraphId,
                "datasetCount", datasets.size(), "analysisMode", mode.name()));
        Map<String, AnalysisDispatchCoordinator.Outcome> outcomes;
        try {
            outcomes = mode == DatasetAnalysisMode.PER_DATASET_WORKERS
                ? dispatchPerDataset(request, computation.get(), datasetRegistry)
                : new com.chatchat.agents.orchestration.analysis.graph.UnifiedQuestionAnalysisGraph(
                    profiles, configuration.adaptivePromptModelEnabled(), configuration.maximumEvidenceRounds(),
                    configuration.reportDraftEnabled()).execute(
                    request.query(), datasets, computation, request.model(), request.isolationScope(),
                    request.summaryProtocol(), spillStore, request.metadata(), request.cancellationGuard());
        } catch (RuntimeException failure) {
            observe(request, "数据分析调度未完成。",
                "analysis_graph", metadataOf("type", "dataset_analysis_failed",
                    "eventKind", "ANALYSIS_GRAPH", "eventState",
                    request.cancellationCheck().getAsBoolean() ? "CANCELLED" : "FAILED",
                    "graphId", analysisGraphId, "datasetCount", datasets.size(),
                    "errorType", failure.getClass().getSimpleName()));
            throw failure;
        }
        lifecycle = lifecycle.datasetsDispatched(datasets.size());
        CoverageBundle coverage;
        try {
            coverage = reconcile(request, datasets, relationshipPlan, lifecycle, outcomes, prepared,
                datasetRegistry);
        } catch (RuntimeException failure) {
            observe(request, "Worker 结果汇集或编排失败。",
                "analysis_graph", metadataOf("type", "dataset_analysis_failed",
                    "eventKind", "ANALYSIS_GRAPH", "eventState", "FAILED", "graphId", analysisGraphId,
                    "datasetCount", datasets.size(), "errorType", failure.getClass().getSimpleName()));
            throw failure;
        }
        if (coverage.evidenceTraceComplete() && coverage.coverageComplete()) {
            observe(request, "全部 " + datasets.size() + " 个数据集的预处理结果均已收集。",
                "analysis_graph", metadataOf("type", "dataset_analysis_completed",
                    "eventKind", "ANALYSIS_GRAPH", "eventState", "COMPLETED", "graphId", analysisGraphId,
                    "datasetCount", datasets.size(), "outcomeCount", outcomes.size()));
        } else {
            observe(request, "部分数据集的预处理或来源覆盖不完整，Driver 将基于现有输入继续分析。",
                "analysis_graph", metadataOf("type", "dataset_analysis_partial",
                    "eventKind", "ANALYSIS_GRAPH", "eventState", "PARTIAL", "graphId", analysisGraphId,
                    "datasetCount", datasets.size(), "outcomeCount", outcomes.size(),
                    "degradedDatasetCount", request.metadata().getOrDefault("analysisDegradedDatasetCount", 0)));
        }
        return coverage;
    }

    private DatasetAnalysisMode selectMode(List<AnalysisEvidenceCoordinator.Dataset> datasets) {
        long estimatedChars = datasets.stream().mapToLong(dataset ->
            dataset.handle().estimatedSizeBytes().orElse(dataset.recordCount() * 512L)).sum();
        return dispatchCoordinator != null
            && (datasets.size() >= configuration.perDatasetWorkerThreshold()
                || estimatedChars >= configuration.perDatasetWorkerTotalCharsThreshold())
            ? DatasetAnalysisMode.PER_DATASET_WORKERS : DatasetAnalysisMode.UNIFIED_QUESTION;
    }

    private Map<String, AnalysisDispatchCoordinator.Outcome> dispatchPerDataset(
        Request request, List<AnalysisEvidenceCoordinator.Dataset> datasets,
        DatasetExecutionRegistry datasetRegistry
    ) {
        List<AnalysisDispatchCoordinator.DatasetInput> inputs = datasets.stream()
            .map(dataset -> new AnalysisDispatchCoordinator.DatasetInput(
                dataset.reference(), dataset.analysisContext(), dataset.records()))
            .toList();
        AnalysisDispatchCoordinator.DispatchRequest dispatchRequest =
            new AnalysisDispatchCoordinator.DispatchRequest(
                request.model(), request.query(), "", inputs, request.isolationScope(),
                request.runtimeAttributes(), request.cancellationCheck());
        Map<String, AnalysisDispatchCoordinator.Outcome> outcomes = new LinkedHashMap<>();
        try (AnalysisDispatchCoordinator.DispatchBatch batch = dispatchCoordinator.dispatch(
            dispatchRequest, datasetRegistry)) {
            request.metadata().put("recordAnalysisSummaryParallel", batch.isParallel());
            request.metadata().put("recordAnalysisSummaryScheduledTaskCount", batch.taskCount());
            request.metadata().put("recordAnalysisSummaryWorkerCount", batch.workerCount());
            request.metadata().put("recordAnalysisWorkerTransportMode", batch.mode());
            DatasetReferenceSequence references = datasetReferences(datasets);
            for (AnalysisEvidenceCoordinator.Dataset dataset : datasets) {
                String reference = references.next(dataset.reference());
                outcomes.put(reference, batch.await(reference));
            }
            request.metadata().put("datasetWorkerExecutionRegistry",
                batch.datasets().stream().map(
                    com.chatchat.agents.orchestration.analysis.execution.DatasetExecutionState::toMap).toList());
        }
        return Map.copyOf(outcomes);
    }

    private List<AnalysisEvidenceCoordinator.Dataset> externalizeLargeDatasets(
        List<AnalysisEvidenceCoordinator.Dataset> datasets, Request request) {
        if (datasets == null || datasets.isEmpty() || spillStore == null || !spillStore.isEnabled())
            return datasets == null ? List.of() : datasets;
        int spilled = 0;
        List<AnalysisEvidenceCoordinator.Dataset> result = new ArrayList<>(datasets.size());
        for (AnalysisEvidenceCoordinator.Dataset dataset : datasets) {
            if (dataset.handle() instanceof com.chatchat.agents.orchestration.analysis.dataset.InMemoryDatasetHandle
                && dataset.handle().estimatedSizeBytes().orElse(0) >= spillStore.spillThresholdBytes()) {
                try {
                    var handle = com.chatchat.agents.orchestration.analysis.dataset.SpillDatasetHandle.capture(
                        dataset.reference(), dataset.handle(), spillStore, request.isolationScope(), 1_000);
                    result.add(new AnalysisEvidenceCoordinator.Dataset(dataset.reference(),
                        dataset.analysisContext(), handle));
                    spilled++;
                } catch (RuntimeException failure) {
                    result.add(dataset);
                    request.metadata().put("analysisDatasetSpillFallback", true);
                    request.metadata().put("analysisDatasetSpillFailureType", failure.getClass().getSimpleName());
                }
            } else result.add(dataset);
        }
        request.metadata().put("analysisDatasetHandleCount", result.size());
        request.metadata().put("analysisSpillDatasetHandleCount", spilled);
        return List.copyOf(result);
    }

    private CoverageBundle reconcile(
        Request request,
        List<AnalysisEvidenceCoordinator.Dataset> datasets,
        DatasetRelationshipPlan relationshipPlan,
        DataAnalysisLifecycle initialLifecycle,
        Map<String, AnalysisDispatchCoordinator.Outcome> outcomes,
        Map<String, PreparedCalculation> prepared,
        DatasetExecutionRegistry datasetRegistry
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
        List<com.chatchat.agents.orchestration.analysis.report.ObservedReportData> observedData = new ArrayList<>();
        request.metadata().put("runtimeObservedReportData", List.of());
        List<com.chatchat.agents.orchestration.analysis.report.ReturnedReportDataset> reportDatasets = new ArrayList<>();
        request.metadata().put("runtimeReturnedReportDatasets", List.of());
        List<Map<String, Object>> insightDecisions = new ArrayList<>();
        List<Map<String, Object>> presentationViews = new ArrayList<>();
        List<Map<String, Object>> datasetDepthMetrics = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>(sourceFailureDatasets(request.metadata()));
        List<DataAnalysisWorkerSupervision.WorkerReport> workerReports = new ArrayList<>();
        WorkerPreprocessingCollector workerSupervisor = new WorkerPreprocessingCollector();
        DatasetReferenceSequence references = datasetReferences(datasets);
        Counters counters = new Counters();

        int datasetIndex = 0;
        for (AnalysisEvidenceCoordinator.Dataset dataset : datasets) {
            datasetIndex++;
            request.cancellationGuard().run();
            String reference = references.next(dataset.reference());
            counters.returned += Math.toIntExact(dataset.recordCount());
            counters.sourceComplete &= dataset.records().stream()
                .noneMatch(record -> Boolean.FALSE.equals(record.get("sourceComplete")));
            // Returned data exists independently of the Worker product. Capture the bounded
            // Driver projection before validating model-authored analysis so a Worker protocol
            // failure cannot erase source evidence from final synthesis.
            if (reportDatasets.size() < 12) {
                reportDatasets.add(com.chatchat.agents.orchestration.analysis.report.ReturnedReportDataset.capture(
                    reference, dataset.records(), dataset.analysisContext()));
                request.metadata().put("runtimeReturnedReportDatasets", List.copyOf(reportDatasets));
            }
            if ("PYTHON_JSON_STDOUT_RECORDS".equals(dataset.analysisContext().get("projectionMode"))) {
                observedData.addAll(com.chatchat.agents.orchestration.analysis.report.ObservedReportData.capture(
                    reference, dataset.records()));
                request.metadata().put("runtimeObservedReportData", List.copyOf(observedData));
            }
            AnalysisDispatchCoordinator.Outcome outcome = outcomes.get(reference);
            DataAnalysisWorkerSupervision.WorkerReport workerReport = workerSupervisor.inspect(
                reference, Math.toIntExact(dataset.recordCount()), outcome,
                evidenceCoordinator::hasTraceableEvidence);
            workerReports.add(workerReport);
            observeWorkerSupervision(request, workerReport, datasetIndex, datasets.size());
            if (outcome != null && "SKIPPED".equalsIgnoreCase(outcome.status())) {
                datasetRegistry.skipped(reference, outcome.error());
                continue;
            }
            if (outcome == null || outcome.summary() == null) {
                datasetRegistry.failed(reference, outcome == null
                    ? "missing preprocessing result" : outcome.error());
                recordFailure(request, prompt, appendix, failures, reference, datasetIndex,
                    datasets.size(), Math.toIntExact(dataset.recordCount()), outcome);
                continue;
            }
            AnalysisDatasetSummary summary = outcome.summary();
            datasetDepthMetrics.add(datasetDepthMetric(
                reference, summary.datasetSummary(), Math.toIntExact(dataset.recordCount())));
            request.metadata().put("analysisDatasetDepthMetrics", List.copyOf(datasetDepthMetrics));
            datasetRegistry.analyzed(reference, List.of(summary.datasetSummary().content()),
                evidenceIds(summary), truncatedDataset(request.metadata(), reference));
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
            PreparedCalculation calculation = prepared.get(reference);
            insightDatasets.addAll(calculation.inputs());
            insightResults.addAll(calculation.results());
            insightDecisions.addAll(calculation.decisions());
            prompt.append(calculation.prompt());
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

        request.metadata().put("datasetExecutionRegistry", datasetRegistry.snapshotMap());
        request.metadata().put("datasets", datasetRegistry.snapshot().stream()
            .map(com.chatchat.agents.orchestration.analysis.execution.DatasetExecutionState::toMap)
            .toList());
        request.metadata().put("missingDatasets", datasetRegistry.missingDatasetIds());
        List<String> successfulReferences = datasetRegistry.snapshot().stream()
            .filter(state -> state.status()
                == com.chatchat.agents.orchestration.analysis.execution.DatasetAnalysisStatus.ANALYZED
                || state.status()
                == com.chatchat.agents.orchestration.analysis.execution.DatasetAnalysisStatus.TRUNCATED)
            .map(com.chatchat.agents.orchestration.analysis.execution.DatasetExecutionState::datasetId)
            .toList();
        List<String> failedReferences = datasetRegistry.snapshot().stream()
            .filter(state -> state.status()
                == com.chatchat.agents.orchestration.analysis.execution.DatasetAnalysisStatus.FAILED)
            .map(com.chatchat.agents.orchestration.analysis.execution.DatasetExecutionState::datasetId)
            .toList();
        List<String> sourceFailedReferences = sourceFailedDatasetReferences(request.metadata());
        failedReferences = java.util.stream.Stream.concat(
            failedReferences.stream(), sourceFailedReferences.stream()).distinct().toList();
        List<String> excludedReferences = excludedDatasetReferences(request.metadata());
        List<String> skippedReferences = datasetRegistry.snapshot().stream()
            .filter(state -> state.status()
                == com.chatchat.agents.orchestration.analysis.execution.DatasetAnalysisStatus.SKIPPED)
            .map(com.chatchat.agents.orchestration.analysis.execution.DatasetExecutionState::datasetId)
            .toList();
        excludedReferences = java.util.stream.Stream.concat(
            excludedReferences.stream(), skippedReferences.stream()).distinct().toList();
        DatasetCompletionSnapshot completion = new DatasetCompletionSnapshot(
            datasets.size() + projectionAccountingCount(request.metadata()), excludedReferences.size(), successfulReferences.size(),
            failedReferences.size(), successfulReferences, failedReferences, excludedReferences);
        request.metadata().put("datasetCompletionSnapshot", completion.toMap());
        request.metadata().put("analysisCompletionOutcome",
            completion.partial() ? "PARTIAL" : failedReferences.isEmpty() ? "SUCCESS" : "FAILED");
        prompt.append("Dataset completion snapshot: ")
            .append(ModelProtocolJson.compact(completion.toMap())).append("\n")
            .append("The final report must explicitly list successful, failed, and excluded datasets. ")
            .append("When any failed or excluded dataset exists, label the report PARTIAL and never infer facts from it.\n");
        if (!datasetRegistry.synthesisReady()) {
            throw new IllegalStateException("Dataset synthesis barrier is not ready; missing="
                + datasetRegistry.missingDatasetIds());
        }

        DataAnalysisWorkerSupervision.DriverReport supervision =
            new DataAnalysisWorkerSupervision().reconcile(datasets.size(), workerReports);
        writeSupervisionMetadata(request, supervision);
        DataAnalysisLifecycle lifecycle = initialLifecycle.workersReconciled(
            supervision.acceptedWorkerCount(), supervision.rejectedWorkerCount());
        if (!supervision.synthesisReady()) {
            prompt.append("No Worker preprocessing output was available. This is a preprocessing "
                + "failure, not absence of data. Continue Driver synthesis from the bounded returned-data "
                + "projection and disclose the preprocessing failure.\n");
            request.metadata().put("analysisWorkerFallbackToReturnedData", true);
            request.metadata().put("analysisSynthesisBarrierStatus",
                "READY_WITHOUT_WORKER_PREPROCESSING");
        }
        // The unified question graph has already analyzed all datasets together. Routing its
        // findings back through the legacy per-dataset/relationship Reducer destroys question-level
        // meaning and can discard model-owned calibrated inferences. Preserve the validated unified
        // products as the final synthesis inputs; Runtime continues to audit evidence bindings.
        boolean workerMode = DatasetAnalysisMode.PER_DATASET_WORKERS.name().equals(
            request.metadata().get("datasetAnalysisMode"));
        DeterministicInsightEngine.Result bundleInsights;
        StructuredFindingMerger.Result hierarchy;
        if (workerMode) {
            FinalSynthesisNode.HierarchicalSynthesisResult synthesis =
                synthesisCoordinator.synthesizeHierarchy(
                    new FinalSynthesisNode.HierarchicalSynthesisRequest(
                        instruction -> request.model().chat(instruction), request.isolationScope(),
                        relationshipPlan, request.query(), List.copyOf(datasetSummaries),
                        List.copyOf(insightDatasets), lifecycle, request.runtimeAttributes()));
            bundleInsights = synthesis.crossDatasetInsights();
            hierarchy = synthesis.hierarchy();
            lifecycle = synthesis.lifecycle();
        } else {
            bundleInsights = insightEngine.analyzeBundle(request.isolationScope(), insightDatasets);
            List<String> uncovered = datasetSummaries.stream()
                .filter(summary -> summary.content() == null || summary.content().isBlank())
                .map(AnalysisSummaryResult::scope).toList();
            hierarchy = new StructuredFindingMerger.Result(
                relationshipPlan, List.copyOf(datasetSummaries), List.of(),
                List.copyOf(datasetSummaries), uncovered);
            lifecycle = lifecycle.finalSummaryCompleted(datasetSummaries.size());
        }
        request.metadata().put("analysisFinalInputMode", workerMode
            ? "PER_DATASET_SUMMARIES" : "UNIFIED_QUESTION_FINDINGS");
        request.metadata().put("analysisLegacyReducerBypassed", !workerMode);
        observe(request, "统一问题分析结果已完成证据绑定，可进入最终报告综合。",
            "analysis_summary_governance", metadataOf(
                "type", "unified_analysis_ready_for_synthesis",
                "analysisResultCount", datasetSummaries.size(),
                "uncoveredDatasetCount", hierarchy.uncoveredDatasets().size(),
                "legacyReducerBypassed", !workerMode));
        if (bundleInsights.executed()
            && (!bundleInsights.findings().isEmpty() || !bundleInsights.issues().isEmpty())) {
            insightResults.add(bundleInsights.toMap());
            prompt.append("Cross-dataset deterministic findings (authoritative calculations): ")
                .append(ModelProtocolJson.compact(bundleInsights.toMap())).append("\n");
        }
        boolean allAnalysisProductsAccepted = workerReports.size() == datasets.size()
            && workerReports.stream().allMatch(
                DataAnalysisWorkerSupervision.WorkerReport::acceptedForSynthesis);
        request.metadata().put("analysisAllDatasetProductsAccepted", allAnalysisProductsAccepted);
        request.metadata().put("analysisDegradedDatasetCount", 0L);
        request.metadata().put("analysisUnavailablePreprocessingDatasetCount", workerReports.stream()
            .filter(report -> !report.acceptedForSynthesis()).count());
        boolean coverageComplete = counters.processed == counters.returned;
        boolean traceComplete = counters.processed > 0
            && governedSummaries.size() == counters.iterations
            && governedSummaries.stream().allMatch(evidenceCoordinator::hasTraceableEvidence)
            && governedSummaries.stream().map(AnalysisSummaryResult::resultId).distinct().count()
                == governedSummaries.size();
        if (hierarchy.finalInputs().isEmpty()) {
            traceComplete = false;
            prompt.append("The unified analysis produced no evidence-bound findings. Publish the "
                + "available-data limitation and do not claim that successfully returned datasets "
                + "were absent.\n");
        }
        appendCoverage(prompt, hierarchy, failures, counters, coverageComplete, traceComplete);
        if (!rawReplay.isEmpty()) {
            // Raw replay is retained for local repair and diagnostics. Final synthesis consumes the
            // evidence-bound unified analysis products instead of reinterpreting all raw rows.
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

    private void observeWorkerSupervision(
        Request request,
        DataAnalysisWorkerSupervision.WorkerReport report,
        int index,
        int count
    ) {
        boolean accepted = report.acceptedForSynthesis();
        String stage = accepted ? "PREPROCESSING_AVAILABLE" : "PREPROCESSING_UNAVAILABLE";
        observe(request, "第 " + index + "/" + count + " 组数据预处理"
                + (accepted ? "已完成，结果将完整交给 Driver。" : "未产生可用输出，Driver 将使用已返回数据。"),
            "business_analysis_progress", metadataOf(
                "type", "analysis_worker_supervision",
                "stage", stage,
                "workReference", report.datasetReference(),
                "workIndex", index,
                "workCount", count,
                "workerReport", report.toMap()));
    }

    private void writeSupervisionMetadata(
        Request request,
        DataAnalysisWorkerSupervision.DriverReport supervision
    ) {
        if (request.metadata() != null) {
            request.metadata().put("analysisWorkerSupervision", supervision.toMap());
            request.metadata().put("analysisWorkerSupervisionSchemaVersion",
                DataAnalysisWorkerSupervision.SCHEMA_VERSION);
            // This is preprocessing accounting only. It has no semantic-quality or publication
            // authority; every available Worker result is passed to the Driver.
            request.metadata().put("analysisSynthesisBarrierReady", true);
            request.metadata().put("analysisSynthesisBarrierStatus",
                supervision.synthesisReady()
                    ? supervision.barrierStatus().name()
                    : "READY_WITHOUT_WORKER_PREPROCESSING");
            request.metadata().put("analysisRepairRequired", false);
            request.metadata().put("analysisAcceptedWorkerCount",
                supervision.acceptedWorkerCount());
            request.metadata().put("analysisRejectedWorkerCount",
                supervision.rejectedWorkerCount());
            request.metadata().put("analysisAvailableWorkerPreprocessingCount",
                supervision.acceptedWorkerCount());
            request.metadata().put("analysisUnavailableWorkerPreprocessingCount",
                supervision.rejectedWorkerCount());
        }
        observe(request,
            supervision.synthesisReady()
                ? "Worker 预处理结果已收齐，正在交给 Driver 综合分析。"
                : "Worker 预处理已完成对账；缺失部分由 Driver 根据已返回数据继续分析。",
            "business_analysis_progress", metadataOf(
                "type", "analysis_driver_barrier",
                "stage", supervision.synthesisReady()
                    ? "PREPROCESSING_COLLECTED" : "PREPROCESSING_PARTIAL",
                "workReference", supervision.expectedWorkerCount() == 1
                    ? supervision.workers().get(0).datasetReference() : "all-datasets",
                "workIndex", supervision.terminalWorkerCount(),
                "workCount", supervision.expectedWorkerCount(),
                "supervision", supervision.toMap()));
    }

    private record PreparedCalculation(List<DeterministicInsightEngine.DatasetInput> inputs,
        List<Map<String, Object>> results, List<Map<String, Object>> decisions, String prompt) {}

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
                request.isolationScope(), reference, contract, dataset.handle());
            inputs.add(new DeterministicInsightEngine.DatasetInput(reference, contract, dataset.handle()));
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

    private void appendCoverage(StringBuilder prompt, StructuredFindingMerger.Result hierarchy,
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

    private List<String> excludedDatasetReferences(Map<String, Object> metadata) {
        if (metadata == null
            || !(metadata.get("recordAnalysisExcludedDatasets") instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> references = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)) continue;
            if ("FAILED".equals(String.valueOf(item.get("accountingStatus")))) continue;
            Object reference = item.get("datasetReference");
            if (reference != null && !String.valueOf(reference).isBlank()) {
                references.add(String.valueOf(reference));
            }
        }
        return references.stream().distinct().toList();
    }

    private List<String> sourceFailedDatasetReferences(Map<String, Object> metadata) {
        if (metadata == null
            || !(metadata.get("recordAnalysisExcludedDatasets") instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> references = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)
                || !"FAILED".equals(String.valueOf(item.get("accountingStatus")))) continue;
            Object reference = item.get("datasetReference");
            if (reference != null && !String.valueOf(reference).isBlank()) {
                references.add(String.valueOf(reference));
            }
        }
        return references.stream().distinct().toList();
    }

    private List<Map<String, Object>> sourceFailureDatasets(Map<String, Object> metadata) {
        if (metadata == null
            || !(metadata.get("recordAnalysisExcludedDatasets") instanceof Iterable<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)
                || !"FAILED".equals(String.valueOf(item.get("accountingStatus")))) continue;
            Map<String, Object> failure = new LinkedHashMap<>();
            item.forEach((key, entry) -> failure.put(String.valueOf(key), entry));
            failures.add(Map.copyOf(failure));
        }
        return List.copyOf(failures);
    }

    private int projectionAccountingCount(Map<String, Object> metadata) {
        return excludedDatasetReferences(metadata).size()
            + sourceFailedDatasetReferences(metadata).size();
    }

    private DatasetReferenceSequence datasetReferences(
        List<AnalysisEvidenceCoordinator.Dataset> datasets
    ) {
        return new DatasetReferenceSequence(datasets.stream()
            .map(AnalysisEvidenceCoordinator.Dataset::reference).toList());
    }

    private void writeEmptyProjectionCompletion(
        Request request, List<Map<String, Object>> projectionExclusions
    ) {
        List<String> failed = sourceFailedDatasetReferences(request.metadata());
        List<String> excluded = excludedDatasetReferences(request.metadata());
        DatasetCompletionSnapshot completion = new DatasetCompletionSnapshot(
            failed.size() + excluded.size(), excluded.size(), 0, failed.size(),
            List.of(), failed, excluded);
        request.metadata().put("datasetCompletionSnapshot", completion.toMap());
        request.metadata().put("analysisCompletionOutcome",
            failed.isEmpty() ? "NO_DATA" : "FAILED");
        request.metadata().put("recordAnalysisFailedDatasetCount", failed.size());
        request.metadata().put("recordAnalysisFailedDatasets", projectionExclusions.stream()
            .filter(item -> "FAILED".equals(item.get("accountingStatus"))).toList());
        request.metadata().put("recordAnalysisAllWorkersFailed", !failed.isEmpty());
    }

    private Map<String, Object> datasetDepthMetric(
        String reference, AnalysisSummaryResult summary, int totalRecords
    ) {
        List<Map<String, Object>> findings = new ArrayList<>();
        findings.addAll(mapValues(summary.evidence().get("observedFactClaims")));
        findings.addAll(mapValues(summary.evidence().get("insights")));
        long citedRecords = findings.stream()
            .flatMap(finding -> stringValues(finding.get("recordRefs")).stream())
            .distinct().count();
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("datasetReference", reference);
        metric.put("findingCount", findings.size());
        metric.put("citedRecordRefCount", citedRecords);
        metric.put("totalRecordCount", Math.max(0, totalRecords));
        metric.put("citedRecordRatio", totalRecords <= 0 ? 0D
            : Math.min(1D, (double) citedRecords / totalRecords));
        metric.put("advisoryOnly", true);
        return Map.copyOf(metric);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapValues(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return List.copyOf(result);
    }

    private List<String> stringValues(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        }
        return List.copyOf(result);
    }

    private void writeResultMetadata(Request request, int datasetCount,
        DatasetRelationshipPlan relationships, DataAnalysisLifecycle lifecycle,
        StructuredFindingMerger.Result hierarchy, List<AnalysisSummaryResult> summaries,
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
            "schemaVersion", StructuredFindingMerger.SCHEMA_VERSION,
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
                                long heartbeatTimeoutMs, boolean adaptivePromptModelEnabled,
                                int maximumEvidenceRounds, boolean reportDraftEnabled,
                                int perDatasetWorkerThreshold,
                                long perDatasetWorkerTotalCharsThreshold) {
        public Configuration {
            perDatasetWorkerThreshold = Math.max(1, perDatasetWorkerThreshold);
            perDatasetWorkerTotalCharsThreshold = Math.max(2_000L,
                perDatasetWorkerTotalCharsThreshold);
        }
        public Configuration(int maximumRetries, long heartbeatIntervalMs, long heartbeatTimeoutMs) {
            this(maximumRetries, heartbeatIntervalMs, heartbeatTimeoutMs, true, 2, false,
                3, 24_000L);
        }
        public Configuration(int maximumRetries, long heartbeatIntervalMs, long heartbeatTimeoutMs,
                             boolean adaptivePromptModelEnabled, int maximumEvidenceRounds,
                             boolean reportDraftEnabled) {
            this(maximumRetries, heartbeatIntervalMs, heartbeatTimeoutMs,
                adaptivePromptModelEnabled, maximumEvidenceRounds, reportDraftEnabled,
                3, 24_000L);
        }
    }

    private List<String> evidenceIds(AnalysisDatasetSummary summary) {
        List<String> values = new ArrayList<>();
        Object datasetEvidenceId = summary.datasetSummary().evidence().get("evidenceId");
        if (datasetEvidenceId != null && !String.valueOf(datasetEvidenceId).isBlank()) {
            values.add(String.valueOf(datasetEvidenceId));
        }
        summary.chunks().stream()
            .map(chunk -> chunk.summary().evidence().get("evidenceId"))
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf).filter(value -> !value.isBlank())
            .filter(value -> !values.contains(value)).forEach(values::add);
        return List.copyOf(values);
    }

    private boolean truncatedDataset(Map<String, Object> metadata, String reference) {
        Object raw = metadata == null ? null : metadata.get("adaptiveAnalysisPromptTruncatedDatasets");
        return raw instanceof List<?> values && values.stream()
            .map(String::valueOf).anyMatch(reference::equals);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

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
