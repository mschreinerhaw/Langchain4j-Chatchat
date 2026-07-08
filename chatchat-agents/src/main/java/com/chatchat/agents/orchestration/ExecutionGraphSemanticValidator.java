package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates semantic consistency across an executed InterpretationPlan path.
 */
class ExecutionGraphSemanticValidator {

    static final String SCHEMA_VERSION = "execution_graph_semantic_state_v1";

    Map<String, Object> validate(String query,
                                 InterpretationPlanRuntime.ExecutionResult result,
                                 Map<String, Object> sqlMetadataFact) {
        Map<String, Object> state = new LinkedHashMap<>();
        List<Map<String, Object>> stepValidations = stepValidations(result);
        Map<String, Object> sqlPath = sqlMetadataPath(result, sqlMetadataFact);
        boolean graphSucceeded = result != null && result.success();
        boolean allExecutedStepsValid = stepValidations.stream()
            .allMatch(step -> Boolean.TRUE.equals(step.get("valid")));
        boolean finalAnswerReached = stepValidations.stream()
            .anyMatch(step -> "final_answer".equals(step.get("category")) && Boolean.TRUE.equals(step.get("valid")));
        boolean globalIntentMatch = globalIntentMatch(query, sqlMetadataFact);
        boolean joinPathValid = true;
        boolean metricConsistencyValid = true;
        boolean passed = graphSucceeded
            && allExecutedStepsValid
            && finalAnswerReached
            && globalIntentMatch
            && Boolean.TRUE.equals(sqlPath.get("valid"))
            && joinPathValid
            && metricConsistencyValid;

        state.put("schemaVersion", SCHEMA_VERSION);
        state.put("passed", passed);
        state.put("reason", passed ? "execution_graph_semantic_path_validated" : failureReason(
            graphSucceeded,
            allExecutedStepsValid,
            finalAnswerReached,
            globalIntentMatch,
            Boolean.TRUE.equals(sqlPath.get("valid")),
            joinPathValid,
            metricConsistencyValid
        ));
        state.put("globalIntentMatch", globalIntentMatch);
        state.put("stepValidations", stepValidations);
        state.put("sqlMetadataPath", sqlPath);
        state.put("joinPathValid", joinPathValid);
        state.put("metricConsistencyValid", metricConsistencyValid);
        state.put("graphSucceeded", graphSucceeded);
        state.put("allExecutedStepsValid", allExecutedStepsValid);
        state.put("finalAnswerReached", finalAnswerReached);
        return Map.copyOf(state);
    }

    private List<Map<String, Object>> stepValidations(InterpretationPlanRuntime.ExecutionResult result) {
        if (result == null || result.steps() == null || result.steps().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            Map<String, Object> value = new LinkedHashMap<>();
            String category = stepCategory(step);
            boolean valid = step != null && step.success();
            value.put("stepId", step == null ? null : step.stepId());
            value.put("toolName", step == null ? null : step.toolName());
            value.put("actionType", step == null ? null : step.actionType());
            value.put("category", category);
            value.put("valid", valid);
            value.put("reason", valid ? "step_success" : firstNonBlank(step == null ? null : step.errorMessage(), "step_failed"));
            value.entrySet().removeIf(entry -> entry.getValue() == null);
            values.add(Map.copyOf(value));
        }
        return List.copyOf(values);
    }

    private Map<String, Object> sqlMetadataPath(InterpretationPlanRuntime.ExecutionResult result,
                                                Map<String, Object> sqlMetadataFact) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (sqlMetadataFact == null || sqlMetadataFact.isEmpty()) {
            value.put("valid", true);
            value.put("reason", "no_sql_metadata_fact");
            return Map.copyOf(value);
        }
        Integer stepId = integerValue(sqlMetadataFact.get("stepId"));
        InterpretationPlanRuntime.StepExecution sourceStep = findStep(result, stepId);
        boolean sourceStepFound = sourceStep != null;
        boolean sourceStepSucceeded = sourceStepFound && sourceStep.success();
        boolean tableGatePassed = Boolean.TRUE.equals(sqlMetadataFact.get("semanticGatePassed"));
        boolean priorStepsSucceeded = priorStepsSucceeded(result, stepId);
        boolean valid = sourceStepFound && sourceStepSucceeded && tableGatePassed && priorStepsSucceeded;
        value.put("valid", valid);
        value.put("reason", valid ? "sql_metadata_source_path_valid" : sqlPathFailureReason(
            sourceStepFound,
            sourceStepSucceeded,
            tableGatePassed,
            priorStepsSucceeded
        ));
        value.put("sourceStepId", stepId);
        value.put("sourceStepFound", sourceStepFound);
        value.put("sourceStepSucceeded", sourceStepSucceeded);
        value.put("tableSemanticGatePassed", tableGatePassed);
        value.put("priorStepsSucceeded", priorStepsSucceeded);
        return Map.copyOf(value);
    }

    private String sqlPathFailureReason(boolean sourceStepFound,
                                        boolean sourceStepSucceeded,
                                        boolean tableGatePassed,
                                        boolean priorStepsSucceeded) {
        List<String> failed = new ArrayList<>();
        if (!sourceStepFound) {
            failed.add("sourceStepFound");
        }
        if (!sourceStepSucceeded) {
            failed.add("sourceStepSucceeded");
        }
        if (!tableGatePassed) {
            failed.add("tableSemanticGatePassed");
        }
        if (!priorStepsSucceeded) {
            failed.add("priorStepsSucceeded");
        }
        return "sql_metadata_path_failed:" + String.join(",", failed);
    }

    private InterpretationPlanRuntime.StepExecution findStep(InterpretationPlanRuntime.ExecutionResult result, Integer stepId) {
        if (result == null || result.steps() == null || stepId == null) {
            return null;
        }
        return result.steps().stream()
            .filter(step -> stepId.equals(step.stepId()))
            .findFirst()
            .orElse(null);
    }

    private boolean priorStepsSucceeded(InterpretationPlanRuntime.ExecutionResult result, Integer stepId) {
        if (result == null || result.steps() == null || stepId == null) {
            return false;
        }
        return result.steps().stream()
            .filter(step -> step != null && step.stepId() != null && step.stepId() < stepId)
            .allMatch(InterpretationPlanRuntime.StepExecution::success);
    }

    private boolean globalIntentMatch(String query, Map<String, Object> sqlMetadataFact) {
        if (sqlMetadataFact == null || sqlMetadataFact.isEmpty()) {
            return true;
        }
        String table = stringValue(sqlMetadataFact.get("table"));
        String requestedTable = stringValue(sqlMetadataFact.get("requestedTable"));
        if (blank(table) || blank(requestedTable)) {
            return false;
        }
        if (!normalizeIdentifier(table).equals(normalizeIdentifier(requestedTable))) {
            return false;
        }
        if (isSqlMetadataSearchColumnFact(sqlMetadataFact)) {
            return true;
        }
        if (blank(query)) {
            return true;
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        return normalizedQuery.contains(table.toLowerCase(Locale.ROOT))
            || normalizedQuery.contains(requestedTable.toLowerCase(Locale.ROOT));
    }

    private boolean isSqlMetadataSearchColumnFact(Map<String, Object> sqlMetadataFact) {
        if (sqlMetadataFact == null || sqlMetadataFact.isEmpty()) {
            return false;
        }
        return "mcp_sql_metadata_search_results_columns".equals(stringValue(sqlMetadataFact.get("source")))
            && Boolean.TRUE.equals(sqlMetadataFact.get("semanticGatePassed"))
            && integerValue(sqlMetadataFact.get("columnCount")) != null
            && integerValue(sqlMetadataFact.get("columnCount")) > 0;
    }

    private String stepCategory(InterpretationPlanRuntime.StepExecution step) {
        if (step == null) {
            return "unknown";
        }
        if ("final_answer".equals(step.actionType())) {
            return "final_answer";
        }
        String tool = step.toolName() == null ? "" : step.toolName().toLowerCase(Locale.ROOT);
        if (tool.contains("sql_datasource_asset_query") || tool.contains("database_asset_search")) {
            return "asset_discovery";
        }
        if (tool.contains("sql_datasource_template_query") || tool.contains("database_ops_template_search")
            || tool.contains("business_query_template_search") || tool.contains("database_query_template_query")) {
            return "template_discovery";
        }
        if (tool.contains("sql_query_execute")) {
            return "sql_execution";
        }
        return tool.isBlank() ? "unknown" : "tool";
    }

    private String failureReason(boolean... flags) {
        List<String> failed = new ArrayList<>();
        String[] names = {
            "graphSucceeded",
            "allExecutedStepsValid",
            "finalAnswerReached",
            "globalIntentMatch",
            "sqlMetadataPathValid",
            "joinPathValid",
            "metricConsistencyValid"
        };
        for (int i = 0; i < flags.length && i < names.length; i++) {
            if (!flags[i]) {
                failed.add(names[i]);
            }
        }
        return "execution_graph_semantic_gate_failed:" + String.join(",", failed);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }
}
