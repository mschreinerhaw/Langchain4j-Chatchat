package com.chatchat.agents.orchestration;

import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Central decision engine for MCP workflow tool execution and final-answer gates.
 */
class AgentWorkflowDecisionEngine {

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String SEARCH_AND_EXTRACT_TOOL = "search_and_extract";

    WorkflowMandatoryResolution resolveWorkflowMandatoryTools(List<String> tools,
                                                              Map<String, Object> runtimeAttributes,
                                                              String query) {
        if (tools == null || tools.isEmpty() || runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return new WorkflowMandatoryResolution(List.of(), List.of());
        }
        Map<String, Object> workflow = workflowConfigMap(runtimeAttributes.get("mcpWorkflow"));
        if (workflow.isEmpty()) {
            return new WorkflowMandatoryResolution(List.of(), List.of());
        }
        Object enabled = workflow.get("enabled");
        if (enabled instanceof Boolean bool && !bool) {
            return new WorkflowMandatoryResolution(List.of(), List.of());
        }
        Object steps = workflow.get("steps");
        if (!(steps instanceof List<?> list) || list.isEmpty()) {
            return new WorkflowMandatoryResolution(List.of(), List.of());
        }

        List<WorkflowToolStep> requiredSteps = new ArrayList<>();
        List<ToolExecutionDecision> skippedDecisions = new ArrayList<>();
        Map<String, Object> conditionContext = workflowConditionContext(runtimeAttributes, query);
        int index = 1;
        for (Object item : list) {
            Map<String, Object> step = asMap(item);
            String tool = stringValue(firstObject(step, "tool", "toolName"));
            List<String> stepTools = new ArrayList<>();
            if (tool != null && !tool.isBlank()) {
                stepTools.add(tool);
            }
            stepTools.addAll(stringList(firstObject(step, "parallelSteps", "parallel_steps")));
            if (stepTools.isEmpty()) {
                index++;
                continue;
            }
            Boolean required = booleanObject(step.get("required"));
            String condition = stringValue(step.get("condition"));
            for (String stepTool : stepTools) {
                ToolExecutionDecision decision = resolveToolExecution(
                    stepTool,
                    !Boolean.FALSE.equals(required),
                    condition,
                    conditionContext,
                    tools,
                    List.of()
                );
                if (decision.outcome() == ToolExecutionOutcome.EXECUTE) {
                    requiredSteps.add(new WorkflowToolStep(firstInteger(firstObject(step, "step", "order"), index), decision.toolName()));
                } else if (decision.outcome() != ToolExecutionOutcome.DEFER_TO_PLANNER) {
                    skippedDecisions.add(decision);
                }
            }
            index++;
        }

        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        requiredSteps.stream()
            .sorted(Comparator.comparingInt(WorkflowToolStep::order))
            .map(WorkflowToolStep::toolName)
            .forEach(tool -> ordered.put(tool, Boolean.TRUE));
        return new WorkflowMandatoryResolution(new ArrayList<>(ordered.keySet()), distinctDecisions(skippedDecisions));
    }

    ToolExecutionDecision resolveToolExecution(String requestedToolName,
                                               boolean required,
                                               String condition,
                                               Map<String, Object> conditionContext,
                                               List<String> availableTools,
                                               List<InteractionToolTrace> traces) {
        String resolvedToolName = normalizeToolName(requestedToolName, availableTools);
        if (resolvedToolName == null || resolvedToolName.isBlank()
            || availableTools == null || !availableTools.contains(resolvedToolName)) {
            return new ToolExecutionDecision(
                firstNonBlank(resolvedToolName, requestedToolName),
                ToolExecutionOutcome.SKIP_POLICY,
                "POLICY_DENIED",
                condition,
                null
            );
        }
        if (hasToolTrace(traces, resolvedToolName)) {
            return new ToolExecutionDecision(
                resolvedToolName,
                ToolExecutionOutcome.SKIP_DUPLICATE,
                "DUPLICATE_TOOL",
                condition,
                null
            );
        }
        if (condition != null && !condition.isBlank()) {
            boolean evaluated = conditionMatches(condition, conditionContext);
            if (!evaluated) {
                return new ToolExecutionDecision(
                    resolvedToolName,
                    ToolExecutionOutcome.SKIP_CONDITION,
                    "CONDITION_NOT_MET",
                    condition,
                    false
                );
            }
            if (required) {
                return new ToolExecutionDecision(
                    resolvedToolName,
                    ToolExecutionOutcome.EXECUTE,
                    "REQUIRED_TOOL",
                    condition,
                    true
                );
            }
        }
        if (required) {
            return new ToolExecutionDecision(
                resolvedToolName,
                ToolExecutionOutcome.EXECUTE,
                "REQUIRED_TOOL",
                condition,
                null
            );
        }
        return new ToolExecutionDecision(
            resolvedToolName,
            ToolExecutionOutcome.DEFER_TO_PLANNER,
            "PLANNER_OPTIONAL",
            condition,
            null
        );
    }

    FinalExecutionDecision resolveFinalExecution(boolean plannerSufficient,
                                                 List<String> mandatoryTools,
                                                 List<InteractionToolTrace> traces,
                                                 Map<String, Object> runtimeAttributes) {
        List<String> missing = missingMandatoryTools(mandatoryTools, traces);
        if (missing.isEmpty()) {
            return new FinalExecutionDecision(true, "REQUIRED_TOOLS_COMPLETED", missing);
        }
        if (plannerSufficient) {
            return new FinalExecutionDecision(true, "PLANNER_SUFFICIENT", missing);
        }
        if (policyAllowsEarlyFinal(runtimeAttributes)) {
            return new FinalExecutionDecision(true, "POLICY_EARLY_EXIT", missing);
        }
        return new FinalExecutionDecision(false, "MISSING_REQUIRED_TOOLS", missing);
    }

    boolean policyAllowsEarlyFinal(Map<String, Object> runtimeAttributes) {
        if (runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return false;
        }
        if (booleanValue(firstObject(runtimeAttributes, "allowEarlyFinal", "allowEarlyExit", "policyAllowEarlyFinal"))) {
            return true;
        }
        Map<String, Object> workflow = workflowConfigMap(runtimeAttributes.get("mcpWorkflow"));
        Map<String, Object> policy = asMap(firstObject(workflow, "policy", "executionPolicy"));
        return booleanValue(firstObject(policy, "allowEarlyFinal", "allowEarlyExit"));
    }

    @SuppressWarnings("unchecked")
    void recordWorkflowDecision(Map<String, Object> metadata, ToolExecutionDecision decision) {
        if (metadata == null || decision == null) {
            return;
        }
        List<Map<String, Object>> records = metadata.get("workflowDecisionRecords") instanceof List<?> existing
            ? (List<Map<String, Object>>) existing
            : new ArrayList<>();
        records.add(decisionRecord(decision));
        metadata.put("workflowDecisionRecords", records);
        if (isSkippedDecision(decision)) {
            List<Map<String, Object>> skipped = metadata.get("workflowSkipDecisions") instanceof List<?> existing
                ? (List<Map<String, Object>>) existing
                : new ArrayList<>();
            skipped.add(decisionRecord(decision));
            metadata.put("workflowSkipDecisions", skipped);
        }
    }

    List<Map<String, Object>> decisionRecords(List<ToolExecutionDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        return decisions.stream()
            .map(this::decisionRecord)
            .toList();
    }

    private Map<String, Object> decisionRecord(ToolExecutionDecision decision) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tool", decision.toolName());
        values.put("status", isSkippedDecision(decision) ? "SKIPPED" : decision.outcome().name());
        values.put("reason", decision.reason());
        if (decision.condition() != null && !decision.condition().isBlank()) {
            values.put("condition", decision.condition());
        }
        if (decision.evaluated() != null) {
            values.put("evaluated", decision.evaluated());
        }
        return values;
    }

    private boolean isSkippedDecision(ToolExecutionDecision decision) {
        return decision != null
            && (decision.outcome() == ToolExecutionOutcome.SKIP_CONDITION
            || decision.outcome() == ToolExecutionOutcome.SKIP_DUPLICATE
            || decision.outcome() == ToolExecutionOutcome.SKIP_POLICY);
    }

    private List<ToolExecutionDecision> distinctDecisions(List<ToolExecutionDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        Map<String, ToolExecutionDecision> indexed = new LinkedHashMap<>();
        for (ToolExecutionDecision decision : decisions) {
            if (decision == null) {
                continue;
            }
            String key = decision.outcome() + ":" + decision.toolName() + ":" + decision.condition();
            indexed.putIfAbsent(key, decision);
        }
        return new ArrayList<>(indexed.values());
    }

    private List<String> missingMandatoryTools(List<String> mandatoryTools, List<InteractionToolTrace> traces) {
        return normalizeList(mandatoryTools).stream()
            .filter(toolName -> !hasToolTrace(traces, toolName))
            .toList();
    }

    private boolean hasToolTrace(List<InteractionToolTrace> traces, String toolName) {
        if (traces == null || traces.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        return traces.stream()
            .anyMatch(trace -> trace != null && trace.isSuccess() && sameToolName(toolName, trace.getToolName()));
    }

    private Map<String, Object> workflowConditionContext(Map<String, Object> runtimeAttributes, String query) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (runtimeAttributes != null) {
            context.putAll(asMap(firstObject(runtimeAttributes, "workflowContext", "workflowVariables")));
        }
        if (query != null && !query.isBlank()) {
            context.put("query", query);
        }
        return context;
    }

    private Map<String, Object> workflowConfigMap(Object rawWorkflow) {
        if (rawWorkflow instanceof List<?> list) {
            Map<String, Object> workflow = new LinkedHashMap<>();
            workflow.put("enabled", true);
            workflow.put("steps", list);
            return workflow;
        }
        return asMap(rawWorkflow);
    }

    private boolean conditionMatches(String condition, Map<String, Object> context) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String expression = condition.trim();
        String[] operators = {">=", "<=", "==", "!=", ">", "<"};
        for (String operator : operators) {
            int index = expression.indexOf(operator);
            if (index <= 0) {
                continue;
            }
            String left = expression.substring(0, index).trim();
            String right = expression.substring(index + operator.length()).trim();
            Object leftValue = context == null ? null : firstObject(context, left, normalizePolicyKey(left));
            return compareCondition(leftValue, operator, right);
        }
        Object value = context == null ? null : firstObject(context, expression, normalizePolicyKey(expression));
        return booleanValue(value);
    }

    private boolean compareCondition(Object leftValue, String operator, String rightText) {
        if (leftValue == null) {
            return false;
        }
        Double leftNumber = doubleValue(leftValue);
        Double rightNumber = doubleValue(unquote(rightText));
        if (leftNumber != null && rightNumber != null) {
            return switch (operator) {
                case ">=" -> leftNumber >= rightNumber;
                case "<=" -> leftNumber <= rightNumber;
                case ">" -> leftNumber > rightNumber;
                case "<" -> leftNumber < rightNumber;
                case "==" -> leftNumber.doubleValue() == rightNumber.doubleValue();
                case "!=" -> leftNumber.doubleValue() != rightNumber.doubleValue();
                default -> false;
            };
        }
        int comparison = String.valueOf(leftValue).compareTo(unquote(rightText));
        return switch (operator) {
            case "==" -> String.valueOf(leftValue).equals(unquote(rightText));
            case "!=" -> !String.valueOf(leftValue).equals(unquote(rightText));
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizePolicyKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String normalizeToolName(String toolName, List<String> availableTools) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String trimmed = toolName.trim();
        if (availableTools == null || availableTools.isEmpty()) {
            return normalizeKnownToolAlias(trimmed);
        }
        if (availableTools.contains(trimmed)) {
            return trimmed;
        }
        String aliased = normalizeKnownToolAlias(trimmed);
        if (availableTools.contains(aliased)) {
            return aliased;
        }
        if (DOCUMENT_SEARCH_TOOL.equals(aliased)) {
            return resolveDocumentSearchTool(availableTools);
        }
        if (WEB_SEARCH_TOOL.equals(aliased)) {
            return resolveVerificationWebSearchTool(availableTools);
        }
        return availableTools.stream()
            .filter(available -> sameToolName(available, trimmed))
            .findFirst()
            .orElse(trimmed);
    }

    private String normalizeKnownToolAlias(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return toolName;
        }
        String semantic = toolSemanticKey(toolName);
        if (semantic.contains("document") && semantic.contains("search")) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search")) {
            return WEB_SEARCH_TOOL;
        }
        if (semantic.contains("search_and_extract")) {
            return SEARCH_AND_EXTRACT_TOOL;
        }
        return toolName.trim();
    }

    private String resolveDocumentSearchTool(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return tools.stream()
            .filter(this::isDocumentSearchToolName)
            .findFirst()
            .orElse(null);
    }

    private String resolveVerificationWebSearchTool(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return tools.stream()
            .filter(this::isWebEvidenceToolName)
            .findFirst()
            .orElseGet(() -> tools.stream()
                .filter(this::isSearchAndExtractToolName)
                .findFirst()
                .orElse(null));
    }

    private boolean isWebEvidenceToolName(String toolName) {
        return isWebSearchToolName(toolName) || isSearchAndExtractToolName(toolName);
    }

    private boolean isWebSearchToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return WEB_SEARCH_TOOL.equals(semantic) || semantic.endsWith("_web_search") || semantic.contains("web_search");
    }

    private boolean isSearchAndExtractToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return SEARCH_AND_EXTRACT_TOOL.equals(semantic) || semantic.endsWith("_search_and_extract") || semantic.contains("search_and_extract");
    }

    private boolean isDocumentSearchToolName(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return DOCUMENT_SEARCH_TOOL.equals(semantic)
            || semantic.endsWith("_document_search")
            || (semantic.contains("document") && semantic.contains("search"));
    }

    private boolean sameToolName(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        String left = first.trim();
        String right = second.trim();
        return left.equals(right)
            || left.equals(normalizeKnownToolAlias(right))
            || normalizeKnownToolAlias(left).equals(right)
            || toolSemanticKey(left).equals(toolSemanticKey(right));
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        String[] prefixes = {
            "chatchat_mcp_server_",
            "chatchat_",
            "xxx_"
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
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

    private List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .distinct()
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            String trimmed = text.trim();
            if (trimmed.contains(",")) {
                List<String> values = new ArrayList<>();
                for (String part : trimmed.split(",")) {
                    if (!part.isBlank()) {
                        values.add(part.trim());
                    }
                }
                return values.stream().distinct().toList();
            }
            return List.of(trimmed);
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Boolean booleanObject(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Object firstObject(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int firstInteger(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private String unquote(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private record WorkflowToolStep(
        int order,
        String toolName
    ) {
    }
}

enum ToolExecutionOutcome {
    EXECUTE,
    SKIP_CONDITION,
    SKIP_DUPLICATE,
    SKIP_POLICY,
    DEFER_TO_PLANNER
}

record ToolExecutionDecision(
    String toolName,
    ToolExecutionOutcome outcome,
    String reason,
    String condition,
    Boolean evaluated
) {
}

record FinalExecutionDecision(
    boolean allowed,
    String reason,
    List<String> missingMandatoryTools
) {
}

record WorkflowMandatoryResolution(
    List<String> tools,
    List<ToolExecutionDecision> skippedDecisions
) {
    List<String> skippedTools() {
        if (skippedDecisions == null || skippedDecisions.isEmpty()) {
            return List.of();
        }
        return skippedDecisions.stream()
            .filter(decision -> decision.outcome() == ToolExecutionOutcome.SKIP_CONDITION)
            .map(ToolExecutionDecision::toolName)
            .filter(toolName -> toolName != null && !toolName.isBlank())
            .distinct()
            .toList();
    }
}
