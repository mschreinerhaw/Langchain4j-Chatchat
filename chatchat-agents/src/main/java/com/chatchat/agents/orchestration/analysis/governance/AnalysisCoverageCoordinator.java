package com.chatchat.agents.orchestration.analysis.governance;
import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;
/** Prepares scoped data access for the single analysis model. No partitioned Agent workflow. */
public final class AnalysisCoverageCoordinator {
    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;
    private final AnalysisEvidenceCoordinator evidenceCoordinator;
    private final Configuration configuration;
    private AnalysisEvidenceSpillStore spillStore;
    private java.util.function.Function<Request, com.chatchat.agents.orchestration.analysis.graph.HarnessToolAccess> toolAccessFactory;
    public void setToolAccessFactory(java.util.function.Function<Request, com.chatchat.agents.orchestration.analysis.graph.HarnessToolAccess> factory) {
        this.toolAccessFactory = factory;
    }
    public AnalysisCoverageCoordinator(AgentRunResultAdapter resultAdapter, String runIdAttribute,
        AnalysisEvidenceCoordinator evidenceCoordinator, AnalysisEvidenceSpillStore spillStore, Configuration configuration) {
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
        this.evidenceCoordinator = evidenceCoordinator;
        this.spillStore = spillStore == null ? AnalysisEvidenceSpillStore.disabled() : spillStore;
        this.configuration = configuration;
    }
    public void setSpillStore(AnalysisEvidenceSpillStore store) {
        this.spillStore = store == null ? AnalysisEvidenceSpillStore.disabled() : store;
    }
    public CoverageBundle analyze(Request request) {
        if (com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.VERSION.equals(request.runtimeAttributes().get("modelAnalysisProtocol")))
            request.metadata().put("modelAnalysisProtocol", com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.VERSION);
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
            "证据入口记录：" + excluded.get("datasetReference") + "（reason="
                + excluded.get("reason") + ", executionStatus=" + excluded.get("executionStatus")
                + (excluded.get("error") == null ? "" : ", error=" + excluded.get("error")) + "）。",
            "analysis_summary_governance", metadataOf(
                "type", "analysis_dataset_excluded", "exclusion", excluded)));
        if (datasets.isEmpty() && !com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.active(request.metadata())) {
            writeEmptyProjectionCompletion(request, projection.excludedDatasets());
            boolean sourceFailed = projection.excludedDatasets().stream().anyMatch(
                item -> "FAILED".equals(item.get("accountingStatus")));
            return new CoverageBundle("", "", List.of(), 0, 0, 0, false,
                !sourceFailed, true, !sourceFailed, 0, List.of(), List.of());
        }

        return analyzeWithHarness(request, datasets);
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
        request.metadata().put("recordAnalysisAllSourcesFailed", !failed.isEmpty());
    }
    private void observe(Request request, String content, String source, Map<String, Object> metadata) {
        Map<String, Object> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        values.put("tenantId", request.isolationScope().tenantId());
        values.put("runId", request.isolationScope().runId());
        resultAdapter.recordRuntimeObservation(
            request.runtimeAttributes(), runIdAttribute, content, source, Map.copyOf(values));
    }
    public record Configuration(int harnessMaxModelTurns) {
        public Configuration { harnessMaxModelTurns = Math.max(1, Math.min(64, harnessMaxModelTurns)); }
    }
    private CoverageBundle analyzeWithHarness(Request request, List<AnalysisEvidenceCoordinator.Dataset> datasets) {
        var skillContext = com.chatchat.agents.runtime.context.SkillAnalysisContext.from(request.runtimeAttributes());
        if (!skillContext.isEmpty()) request.metadata().put("skillAnalysisContext", skillContext);
        else request.metadata().remove("skillAnalysisContext");
        request.metadata().put("agentRoleAnalysisContext",
            com.chatchat.agents.runtime.context.AgentRoleAnalysisContext.fromRuntimeAttributes(request.runtimeAttributes()));
        for (String key : List.of("visualizationAuthorizedTypes", "visualizationSupportedTypes")) {
            if (request.runtimeAttributes().containsKey(key)) request.metadata().put(key, request.runtimeAttributes().get(key));
            else request.metadata().remove(key);
        }
        request.metadata().put("modelNativeHarnessActive", true);
        request.metadata().put("datasetAnalysisMode", "MODEL_NATIVE_HARNESS");
        request.metadata().put("recordAnalysisSummaryDispatchMode", "MODEL_NATIVE_HARNESS");
        request.metadata().put("analysisSemanticReviewPolicy", "NONE_USER_JUDGES");
        var result = new com.chatchat.agents.orchestration.analysis.graph.ModelNativeAnalysisHarness(configuration.harnessMaxModelTurns())
            .withToolAccess(toolAccessFactory == null ? null : toolAccessFactory.apply(request))
            .execute(request.query(), datasets, request.model(), request.isolationScope(), spillStore,
                request.metadata(), request.cancellationGuard(), trace -> observe(request,
                    "STARTED".equals(trace.get("eventState")) ? "Model-directed evidence workspace turn started."
                        : "Model-directed evidence workspace turn finished.", "model_native_harness", trace));
        long rows = datasets.stream().mapToLong(AnalysisEvidenceCoordinator.Dataset::recordCount).sum();
        int count = Math.toIntExact(rows);
        var intentMetadata = new LinkedHashMap<String,Object>();
        for (String key : List.of("modelAnalysisProtocol", "modelDecision", "executionState", "executionStopReason",
            "publicationState", "modelPublicationRequest", "modelEvidenceSnapshotRef", "modelOutput"))
            if (request.metadata().containsKey(key)) intentMetadata.put(key, request.metadata().get(key));
        request.metadata().put("analysisModelIntent", Map.copyOf(intentMetadata));
        var summary = AnalysisSummaryResult.chunk(request.isolationScope(), Map.of("datasetReference", "harness:question",
            "recordFrom", 1, "recordTo", count, "totalRecords", count), Map.of("analysisMode", "MODEL_NATIVE_HARNESS"),
            result.markdown(), "MODEL_AUTHORED", Map.of("authority", "MODEL_AUTHORED_NOT_RUNTIME_CERTIFIED",
                "datasetReferences", result.datasetReferences(),
                "modelEvidenceAssessmentAudit", request.metadata().getOrDefault("modelEvidenceAssessmentAudit", Map.of()),
                "modelEvidenceAssessmentHistory", request.metadata().getOrDefault("modelEvidenceAssessmentHistory", List.of()),
                "modelIntent", Map.copyOf(intentMetadata)));
        request.metadata().put(com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.active(request.metadata()) ? "modelAnalysisOutput" : "modelNativeReportDraft", result.markdown());
        request.metadata().put("analysisSynthesisBarrierReady", true);
        request.metadata().put("analysisSynthesisBarrierStatus", "READY");
        request.metadata().put("recordAnalysisDatasetCount", datasets.size());
        request.metadata().put("recordAnalysisSuccessfulDatasetCount", datasets.size());
        var failures = sourceFailureDatasets(request.metadata());
        boolean[] sourceCompleteness = {true};
        datasets.forEach(dataset -> dataset.handle().scan(1000, page -> {
            request.cancellationGuard().run();
            sourceCompleteness[0] &= page.rows().stream().noneMatch(record -> Boolean.FALSE.equals(record.get("sourceComplete")));
        }));
        boolean sourceComplete = sourceCompleteness[0];
        boolean availableComplete = failures.isEmpty();
        request.metadata().put("recordAnalysisFailedDatasetCount", failures.size());
        request.metadata().put("recordAnalysisReturnedRecordCount", count);
        request.metadata().put("recordAnalysisProcessedRecordCount", count);
        request.metadata().put("recordAnalysisCoverageComplete", availableComplete);
        request.metadata().put("recordAnalysisProcessingMeaning", "RUNTIME_EVIDENCE_ACCESS_PREPARATION_NOT_MODEL_SEMANTIC_COVERAGE");
        request.metadata().put("recordAnalysisEvidenceTraceComplete", availableComplete);
        request.metadata().put("recordAnalysisSourceContentComplete", sourceComplete);
        request.metadata().put("datasetCompletionSnapshot", Map.of("successfulDatasetReferences", result.datasetReferences(),
            "failedDatasetReferences", failures.stream().map(item -> item.get("datasetReference")).toList(), "partial", !availableComplete));
        return new CoverageBundle(com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.active(request.metadata())
                ? "Model-authored working state and scoped evidence workspace retained; publication follows explicit model intent."
                : "Model-authored report and complete scoped evidence workspace retained; no semantic quality gate.",
            "", List.of(), count, count, result.modelCalls(), result.modelCalls() > 1, availableComplete, sourceComplete, availableComplete,
            0, List.of(summary), List.of(summary));
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
}
