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
        "INVALID_TEMPLATE_ID", "INVALID_ASSET_BINDING", "MODEL_SELECTION_INCOMPLETE");

    public static Map<String,Object> describe(InterpretationPlanRuntime.ExecutionResult execution,
            List<AnalysisEvidenceCoordinator.Dataset> datasets, List<Map<String,Object>> exclusions) {
        var failures = new ArrayList<Map<String,Object>>();
        if (execution != null) for (var step : execution.steps()) {
            if (!"mcp_tool".equals(step.actionType())) continue;
            var metadata = step.metadata();
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
            item.put("mcpDiscoverySucceeded", retrieved);
            failures.add(java.util.Collections.unmodifiableMap(item));
        }
        var sources = datasets.stream().filter(dataset ->
            !"DATA_SUPPLY_STATUS".equals(dataset.analysisContext().get("recordType"))).toList();
        long rows = sources.stream().mapToLong(AnalysisEvidenceCoordinator.Dataset::recordCount).sum();
        DataSupplyStatus status = rows > 0 ? DATASET_READY
            : !sources.isEmpty() && sources.stream().allMatch(dataset -> dataset.handle().recordCountExact()
                && Boolean.TRUE.equals(dataset.analysisContext().get("sourceVerifiedEmpty"))) ? EMPTY_RESULT
            : !failures.isEmpty() ? valueOf(failures.get(0).get("status").toString())
            : execution == null && sources.isEmpty() ? NOT_REQUESTED : EXECUTION_FAILED;
        var result = new LinkedHashMap<String,Object>();
        result.put("schemaVersion", VERSION); result.put("status", status.name());
        result.put("datasetCount", sources.size()); result.put("rowCount", rows);
        result.put("datasetReferences", sources.stream().map(AnalysisEvidenceCoordinator.Dataset::reference).toList());
        result.put("businessDataAvailable", rows > 0);
        result.put("deliveryComplete", (status == DATASET_READY || status == EMPTY_RESULT)
            && failures.isEmpty() && exclusions.isEmpty());
        result.put("failures", List.copyOf(failures)); result.put("excludedSourceCount", exclusions.size());
        // A status is never permission to retry, change parameters or execute another asset.
        result.put("automaticRetryAuthorized", false);
        if (status == EXECUTION_FAILED && failures.isEmpty()) {
            result.put("stage", "DATASET_PROJECTION"); result.put("code", "NO_VERIFIED_DATASET");
        }
        return Map.copyOf(result);
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
