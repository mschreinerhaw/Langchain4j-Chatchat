package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Delivery facts, independent of task lifecycle and the model's business assessment. */
public enum DataSupplyStatus {
    SELECTION_FAILED, PARAMETER_INVALID, EXECUTION_FAILED, EMPTY_RESULT, DATASET_READY, NOT_REQUESTED;

    public static final String VERSION = "runtime_os.data_supply.v1";
    private static final Set<String> PARAMETER_ERRORS = Set.of(
        "PARAMETERS_REQUIRED", "INVALID_PARAMETERS", "TEMPLATE_PARAMETERS_REQUIRED",
        "TEMPLATE_REQUIRED_PARAMETERS_MISSING", "TEMPLATE_PARAMETER_BINDING_FAILED");
    private static final Set<String> SELECTION_ERRORS = Set.of(
        "NO_MATCH", "NO_CANDIDATES", "NEEDS_INPUT", "MODEL_UNAVAILABLE", "INVALID_MODEL_RESPONSE",
        "INVALID_TEMPLATE_ID", "INVALID_ASSET_BINDING", "MODEL_SELECTION_INCOMPLETE",
        "TEMPLATE_SELECTION_REQUIRED", "TEMPLATE_BATCH_LIMIT_EXCEEDED", "INCOMPLETE_TEMPLATE_PUBLICATION");

    public static boolean terminalAcquisitionFailure(Map<?, ?> metadata) {
        return Boolean.TRUE.equals(metadata.get("limitedReportAfterUnavailableSupply"))
            && metadata.get("dataSupply") instanceof Map<?, ?> supply
            && Boolean.FALSE.equals(supply.get("businessDataAvailable"))
            && (SELECTION_FAILED.name().equals(supply.get("status"))
                || PARAMETER_INVALID.name().equals(supply.get("status"))
                || EXECUTION_FAILED.name().equals(supply.get("status")));
    }

    public static Map<String,Object> describe(InterpretationPlanRuntime.ExecutionResult execution,
            List<AnalysisEvidenceCoordinator.Dataset> datasets, List<Map<String,Object>> exclusions) {
        var failures = new ArrayList<Map<String,Object>>();
        long published = 0, required = 0, unavailable = 0;
        var requiredIds = new java.util.LinkedHashSet<String>();
        var invokedIds = new java.util.LinkedHashSet<String>();
        var completedIds = new java.util.LinkedHashSet<String>();
        if (execution != null) for (var step : execution.steps()) {
            if (!"mcp_tool".equals(step.actionType())) continue;
            var metadata = step.metadata();
            if (metadata.get("publishedSupplyCounts") instanceof Map<?, ?> counts) {
                published += count(counts.get("publishedTemplateCount"));
                required += count(counts.get("requiredTemplateCount"));
                unavailable += count(counts.get("unavailableRequiredTemplateCount"));
                if (metadata.get("selectedTemplateIds") instanceof Iterable<?> selected)
                    selected.forEach(id -> { if (id instanceof String name) requiredIds.add(name); });
            }
            if (step.output() instanceof com.chatchat.agents.runtime.batch.ToolCallBatchResult batch)
                for (var call : batch.results()) {
                    if (call.templateId() == null) continue;
                    if (call.invoked()) invokedIds.add(call.templateId());
                    if ("SUCCESS".equals(call.status()) && call.evidenceUsable()) completedIds.add(call.templateId());
                }
            String code = text(metadata.get("candidateSelectionErrorCode"));
            if (code == null) code = text(metadata.get("candidateSelectionStatus"));
            var tool=step.toolExecution();
            boolean retrieved=Boolean.TRUE.equals(metadata.get("mcpDiscoverySucceeded"))
                || tool!=null && tool.output()!=null && tool.output().isSuccess();
            if(code==null && retrieved && (Boolean.FALSE.equals(metadata.get("runtimeAssetSelectionApplied"))
                    || Boolean.FALSE.equals(metadata.get("runtimeTemplateSelectionApplied"))))
                code=Boolean.TRUE.equals(metadata.get("toolResultReviewUnavailable"))?"MODEL_UNAVAILABLE":"MODEL_SELECTION_INCOMPLETE";
            DataSupplyStatus failure = code != null && PARAMETER_ERRORS.contains(code) ? PARAMETER_INVALID
                : code != null && SELECTION_ERRORS.contains(code) ? SELECTION_FAILED : null;
            if (failure == null && !step.success()) failure = EXECUTION_FAILED;
            if (failure == null) continue;
            var item = new LinkedHashMap<String,Object>();
            item.put("status", failure.name()); item.put("toolName", step.toolName());
            item.put("stepId", step.stepId());
            item.put("stage", failure == SELECTION_FAILED ? "SELECTION"
                : failure == PARAMETER_INVALID ? "PARAMETERS" : "EXECUTION");
            if (code != null) item.put("code", code);
            String modelFailure = text(metadata.get("selectionModelFailureCategory"));
            if (failure == SELECTION_FAILED && modelFailure != null) item.put("modelFailureCategory", modelFailure);
            item.put("mcpDiscoverySucceeded", retrieved);
            failures.add(java.util.Collections.unmodifiableMap(item));
        }
        var sources = datasets.stream().filter(dataset ->
            !"DATA_SUPPLY_STATUS".equals(dataset.analysisContext().get("recordType"))).toList();
        var incompleteSources = sources.stream().filter(DataSupplyStatus::sourceIncomplete)
            .map(AnalysisEvidenceCoordinator.Dataset::reference).toList();
        boolean incomplete = unavailable > 0 || !incompleteSources.isEmpty() || !failures.isEmpty() || !exclusions.isEmpty();
        long rows = sources.stream().mapToLong(AnalysisEvidenceCoordinator.Dataset::recordCount).sum();
        DataSupplyStatus status = rows > 0 ? DATASET_READY
            : !sources.isEmpty() && sources.stream().allMatch(dataset -> dataset.handle().recordCountExact()
                && !sourceIncomplete(dataset) && Boolean.TRUE.equals(dataset.analysisContext().get("sourceVerifiedEmpty"))) ? EMPTY_RESULT
            : !failures.isEmpty() ? valueOf(failures.get(0).get("status").toString())
            : sources.isEmpty() && exclusions.isEmpty() && noAcquisition(execution) ? NOT_REQUESTED : EXECUTION_FAILED;
        var result = new LinkedHashMap<String,Object>();
        result.put("schemaVersion", VERSION); result.put("status", status.name());
        result.put("datasetCount", sources.size()); result.put("rowCount", rows);
        result.put("datasetReferences", sources.stream().map(AnalysisEvidenceCoordinator.Dataset::reference).toList());
        result.put("businessDataAvailable", rows > 0);
        result.put("deliveryComplete", (status == DATASET_READY || status == EMPTY_RESULT)
            && !incomplete);
        result.put("incompleteDatasetReferences", incompleteSources);
        invokedIds.retainAll(requiredIds);
        completedIds.retainAll(requiredIds);
        result.put("supplyMetrics", Map.of(
            "publishedTemplateCount", published, "requiredTemplateCount", required,
            "unavailableRequiredTemplateCount", unavailable,
            "requiredTemplateExecutedCount", invokedIds.size(), "requiredTemplateCompletedCount", completedIds.size(),
            "datasetCompleteCount", sources.size() - incompleteSources.size(),
            "datasetPartialCount", incompleteSources.size(), "projectedRows", rows));
        result.put("failures", List.copyOf(failures)); result.put("excludedSourceCount", exclusions.size());
        // A status is never permission to retry, change parameters or execute another asset.
        result.put("automaticRetryAuthorized", false);
        var observation=new LinkedHashMap<String,Object>();
        observation.put("schemaVersion",com.chatchat.common.runtime.coding.CodingDataStatus.VERSION);
        observation.put("status",rows>0?(incomplete?"PARTIAL_DATA":"AVAILABLE")
            :status==EMPTY_RESULT?"EMPTY_RESULT":"UNKNOWN");
        observation.put("returnedRowCount",status==EMPTY_RESULT||rows>0?rows:null);
        observation.put("sourceEmptyProven",false);observation.put("cause","UNKNOWN");
        observation.put("automaticRetryAuthorized",false);result.put("dataObservation",observation);
        if (status == EXECUTION_FAILED && failures.isEmpty()) {
            result.put("stage", "DATASET_PROJECTION"); result.put("code", "NO_VERIFIED_DATASET");
        }
        return Map.copyOf(result);
    }

    private static long count(Object value) {
        return value instanceof Number count ? count.longValue() : 0;
    }

    public static boolean sourceIncomplete(AnalysisEvidenceCoordinator.Dataset dataset) {
        return Boolean.FALSE.equals(dataset.analysisContext().get("sourceComplete"));
    }

    private static boolean noAcquisition(InterpretationPlanRuntime.ExecutionResult execution) {
        return execution == null || execution.steps() != null && execution.steps().stream().allMatch(step ->
            step.success() && Set.of("final_answer", "reasoning", "aggregation", "validation").contains(step.actionType()));
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
