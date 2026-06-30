package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Supervises configured MCP orchestration contracts without encoding tool-specific business rules.
 */
class InterpretationPlanWorkflowGuard {

    GuardResult evaluate(InterpretationPlan plan,
                         InterpretationPlanRuntime.ExecutionResult result,
                         List<String> mandatoryTools,
                         List<String> externallyCompletedTools) {
        if (plan == null || result == null || !result.success()) {
            return GuardResult.allowed("not_applicable", Map.of());
        }
        List<String> requiredTools = normalizeList(mandatoryTools);
        List<String> completedTools = mergeCompletedTools(completedTools(result), externallyCompletedTools);
        List<String> executedActions = executedActions(result);
        List<Integer> requiredStepIds = requiredStepIds(plan);
        List<Integer> completedStepIds = completedStepIds(result);
        Map<String, Object> metadata = metadata(requiredTools, completedTools, executedActions, requiredStepIds, completedStepIds);

        List<Integer> missingStepIds = requiredStepIds.stream()
            .filter(required -> !completedStepIds.contains(required))
            .toList();
        if (!missingStepIds.isEmpty()) {
            metadata.put("missingPlanStepIds", missingStepIds);
            return new GuardResult(
                false,
                "interpretation_plan_steps_incomplete",
                "All InterpretationPlan steps must complete before final answer.",
                List.of(),
                missingStepIds,
                metadata
            );
        }

        if (requiredTools.isEmpty()) {
            return GuardResult.allowed("no_mandatory_workflow_configured", metadata);
        }
        List<String> missing = requiredTools.stream()
            .filter(required -> completedTools.stream().noneMatch(completed -> sameSemanticTool(required, completed)))
            .toList();
        if (!missing.isEmpty()) {
            metadata.put("missingRequiredTools", missing);
            return new GuardResult(
                false,
                "mcp_workflow_incomplete",
                "Configured MCP workflow tools must complete before final answer.",
                missing,
                List.of(),
                metadata
            );
        }
        if (!finalAnswerWasLast(result)) {
            return new GuardResult(
                false,
                "final_answer_not_last_step",
                "final_answer must be the last executed InterpretationPlan step.",
                List.of(),
                List.of(),
                metadata
            );
        }
        return GuardResult.allowed("mcp_workflow_complete", metadata);
    }

    private List<Integer> requiredStepIds(InterpretationPlan plan) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return List.of();
        }
        return plan.steps().stream()
            .filter(step -> step != null && step.id() != null)
            .map(InterpretationPlan.Step::id)
            .distinct()
            .toList();
    }

    private List<Integer> completedStepIds(InterpretationPlanRuntime.ExecutionResult result) {
        Set<Integer> completed = new LinkedHashSet<>();
        if (result != null && result.steps() != null) {
            result.steps().stream()
                .filter(step -> step != null && step.success() && step.stepId() != null)
                .map(InterpretationPlanRuntime.StepExecution::stepId)
                .forEach(completed::add);
        }
        Object metadataCompleted = result == null || result.metadata() == null ? null : result.metadata().get("completedPlanStepIds");
        if (metadataCompleted instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Integer value = integerValue(item);
                if (value != null) {
                    completed.add(value);
                }
            }
        }
        return List.copyOf(completed);
    }

    private List<String> completedTools(InterpretationPlanRuntime.ExecutionResult result) {
        if (result.steps() == null || result.steps().isEmpty()) {
            return List.of();
        }
        return result.steps().stream()
            .filter(step -> step != null && step.success())
            .map(InterpretationPlanRuntime.StepExecution::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .distinct()
            .toList();
    }

    private List<String> mergeCompletedTools(List<String> resultCompletedTools, List<String> externallyCompletedTools) {
        Map<String, Boolean> merged = new LinkedHashMap<>();
        normalizeList(externallyCompletedTools).forEach(tool -> merged.put(tool, Boolean.TRUE));
        normalizeList(resultCompletedTools).forEach(tool -> merged.put(tool, Boolean.TRUE));
        return List.copyOf(merged.keySet());
    }

    private List<String> executedActions(InterpretationPlanRuntime.ExecutionResult result) {
        if (result.steps() == null || result.steps().isEmpty()) {
            return List.of();
        }
        return result.steps().stream()
            .filter(step -> step != null && step.success())
            .map(InterpretationPlanRuntime.StepExecution::actionType)
            .filter(action -> action != null && !action.isBlank())
            .toList();
    }

    private boolean finalAnswerWasLast(InterpretationPlanRuntime.ExecutionResult result) {
        if (result.steps() == null || result.steps().isEmpty()) {
            return false;
        }
        int finalIndex = -1;
        for (int index = 0; index < result.steps().size(); index++) {
            InterpretationPlanRuntime.StepExecution step = result.steps().get(index);
            if (step != null && step.success() && "final_answer".equals(step.actionType())) {
                finalIndex = index;
            }
        }
        return finalIndex == result.steps().size() - 1;
    }

    private boolean sameSemanticTool(String expected, String actual) {
        String expectedKey = semanticKey(expected);
        String actualKey = semanticKey(actual);
        return !expectedKey.isBlank()
            && !actualKey.isBlank()
            && (expectedKey.equals(actualKey)
            || actualKey.endsWith(expectedKey)
            || expectedKey.endsWith(actualKey));
    }

    private String semanticKey(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        String[] prefixes = {
            "mcp_chatchat_mcp_server_",
            "chatchat_mcp_server_",
            "mcp_"
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return normalized;
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private Map<String, Object> metadata(List<String> requiredTools,
                                         List<String> completedTools,
                                         List<String> executedActions,
                                         List<Integer> requiredStepIds,
                                         List<Integer> completedStepIds) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", "interpretation_plan_workflow_guard.v1");
        metadata.put("requiredWorkflowTools", requiredTools);
        metadata.put("completedTools", completedTools);
        metadata.put("executedActions", executedActions);
        metadata.put("requiredPlanStepIds", requiredStepIds);
        metadata.put("completedPlanStepIds", completedStepIds);
        metadata.put("singleWriterFinalAnswer", true);
        metadata.put("strictPlanStepCompletion", true);
        return metadata;
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
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record GuardResult(
        boolean allowed,
        String code,
        String reason,
        List<String> missingRequiredTools,
        List<Integer> missingPlanStepIds,
        Map<String, Object> metadata
    ) {
        static GuardResult allowed(String code, Map<String, Object> metadata) {
            return new GuardResult(true, code, "", List.of(), List.of(), metadata);
        }
    }
}
