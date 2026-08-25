package com.chatchat.agents.orchestration;

import com.chatchat.agents.protocol.McpToolProtocolRole;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.McpToolNamePolicy;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.agents.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Central decision engine for MCP workflow tool execution and final-answer gates.
 */
class AgentWorkflowDecisionEngine implements AgentWorkflowDecisionPort {

    private final ToolRegistry toolRegistry;

    AgentWorkflowDecisionEngine() {
        this(null);
    }

    AgentWorkflowDecisionEngine(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String SEARCH_AND_EXTRACT_TOOL = "search_and_extract";

    public WorkflowMandatoryResolution resolveWorkflowMandatoryTools(List<String> tools,
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

        List<WorkflowToolStep> declaredSteps = new ArrayList<>();
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
            int order = firstInteger(step.get("order"), firstInteger(step.get("step"), index));
            List<String> dependencies = stringList(firstObject(step,
                "dependsOn", "depends_on", "requiredDependsOn", "required_depends_on"));
            for (String stepTool : stepTools) {
                ToolExecutionDecision decision = resolveToolExecution(
                    stepTool,
                    !Boolean.FALSE.equals(required),
                    condition,
                    conditionContext,
                    tools,
                    List.of()
                );
                declaredSteps.add(new WorkflowToolStep(
                    order, index, decision.toolName(),
                    workflowStepAliases(step, stepTool, decision.toolName()), dependencies,
                    decision.outcome() == ToolExecutionOutcome.EXECUTE));
                if (decision.outcome() != ToolExecutionOutcome.EXECUTE
                    && decision.outcome() != ToolExecutionOutcome.DEFER_TO_PLANNER) {
                    skippedDecisions.add(decision);
                }
            }
            index++;
        }

        List<WorkflowToolStep> resolvedSteps = withTemplateProtocolDependencies(declaredSteps);
        List<WorkflowToolStep> dependencyOrdered = dependencyOrderedSteps(resolvedSteps);
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        dependencyOrdered.stream()
            .filter(WorkflowToolStep::executable)
            .map(WorkflowToolStep::toolName)
            .forEach(tool -> ordered.put(tool, Boolean.TRUE));
        List<WorkflowDagNode> authoritativeDag = dependencyOrdered.stream()
            .filter(WorkflowToolStep::executable)
            .map(step -> new WorkflowDagNode(
                workflowStepLabel(step),
                step.toolName(),
                step.dependencies().stream()
                    .flatMap(reference -> resolveWorkflowDependency(resolvedSteps, step, reference).stream())
                    .filter(WorkflowToolStep::executable)
                    .map(WorkflowToolStep::toolName)
                    .distinct()
                    .toList(),
                step.order(),
                step.sourceIndex()
            ))
            .toList();
        return new WorkflowMandatoryResolution(
            new ArrayList<>(ordered.keySet()), distinctDecisions(skippedDecisions), authoritativeDag);
    }

    /**
     * Template execution has a transport-level data dependency that cannot be made
     * optional by a planner: asset discovery selects the routing asset, template
     * discovery returns the template id, and only then may execution run. Fill only
     * missing edges for an unambiguous tool family; explicit contradictory edges are
     * retained and will be rejected by the normal cycle validator.
     */
    private List<WorkflowToolStep> withTemplateProtocolDependencies(List<WorkflowToolStep> steps) {
        List<WorkflowToolStep> augmented = new ArrayList<>(steps);
        for (int index = 0; index < augmented.size(); index++) {
            WorkflowToolStep step = augmented.get(index);
            if (workflowRole(step.toolName()) == ToolWorkflowRole.TEMPLATE_DISCOVERY) {
                List<WorkflowToolStep> assets = matchingProtocolPredecessors(
                    augmented, step.toolName(), McpToolProtocolRole.ASSET_QUERY);
                if (assets.size() == 1) {
                    augmented.set(index, withWorkflowDependency(step, workflowStepLabel(assets.get(0))));
                }
                continue;
            }
            if (workflowRole(step.toolName()) == ToolWorkflowRole.TEMPLATE_EXECUTION) {
                List<WorkflowToolStep> queries = matchingProtocolPredecessors(
                    augmented, step.toolName(), McpToolProtocolRole.TEMPLATE_QUERY);
                if (queries.size() == 1) {
                    augmented.set(index, withWorkflowDependency(step, workflowStepLabel(queries.get(0))));
                }
            }
        }
        return augmented;
    }

    private List<WorkflowToolStep> matchingProtocolPredecessors(List<WorkflowToolStep> steps,
                                                                 String toolName,
                                                                 McpToolProtocolRole predecessorRole) {
        McpToolProtocolRole currentRole = predecessorRole == McpToolProtocolRole.ASSET_QUERY
            ? McpToolProtocolRole.TEMPLATE_QUERY : McpToolProtocolRole.TEMPLATE_EXECUTE;
        String declaredFamily = protocolFamily(toolName);
        String resolvedFamily = declaredFamily == null ? currentRole.family(toolName) : declaredFamily;
        if (resolvedFamily != null) {
            List<WorkflowToolStep> familyMatches = matchingTemplateFamily(
                steps, toolName, resolvedFamily, predecessorRole);
            if (!familyMatches.isEmpty()) {
                return familyMatches;
            }
        }
        return steps.stream()
            .filter(candidate -> predecessorRole == McpToolProtocolRole.ASSET_QUERY
                ? workflowRole(candidate.toolName()) == ToolWorkflowRole.ASSET_DISCOVERY
                : workflowRole(candidate.toolName()) == ToolWorkflowRole.TEMPLATE_DISCOVERY)
            .toList();
    }

    private List<WorkflowToolStep> matchingTemplateFamily(List<WorkflowToolStep> steps,
                                                           String currentToolName,
                                                           String family,
                                                           McpToolProtocolRole role) {
        ToolWorkflowRole requiredRole = role == McpToolProtocolRole.ASSET_QUERY
            ? ToolWorkflowRole.ASSET_DISCOVERY : ToolWorkflowRole.TEMPLATE_DISCOVERY;
        return steps.stream()
            .filter(candidate -> !candidate.toolName().equalsIgnoreCase(currentToolName))
            .filter(candidate -> workflowRole(candidate.toolName()) == requiredRole)
            .filter(candidate -> family.equals(protocolFamily(candidate.toolName()))
                || family.equals(role.family(candidate.toolName())))
            .toList();
    }

    private ToolWorkflowRole workflowRole(String toolName) {
        return toolRegistry == null
            ? ToolWorkflowContract.resolveRole(toolName, null)
            : toolRegistry.getWorkflowRole(toolName);
    }

    private String protocolFamily(String toolName) {
        if (toolRegistry == null) return null;
        return ToolWorkflowContract.declaredProtocolFamily(toolRegistry.getToolMetadata(toolName)).orElse(null);
    }

    private WorkflowToolStep withWorkflowDependency(WorkflowToolStep step, String dependency) {
        if (dependency == null || dependency.isBlank()
            || step.dependencies().stream().anyMatch(dependency::equalsIgnoreCase)) {
            return step;
        }
        List<String> dependencies = new ArrayList<>(step.dependencies());
        dependencies.add(dependency);
        return new WorkflowToolStep(step.order(), step.sourceIndex(), step.toolName(),
            step.aliases(), dependencies, step.executable());
    }

    /**
     * Orders mandatory workflow tools by their declared dependency graph. Numeric
     * order and source position are deterministic tie breakers only; treating
     * them as the workflow itself can place an executor before its discovery
     * dependency when generated steps share or omit an order value.
     */
    private List<WorkflowToolStep> dependencyOrderedSteps(List<WorkflowToolStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        Map<WorkflowToolStep, Integer> inDegree = new LinkedHashMap<>();
        Map<WorkflowToolStep, List<WorkflowToolStep>> dependents = new LinkedHashMap<>();
        for (WorkflowToolStep step : steps) {
            inDegree.put(step, 0);
            dependents.put(step, new ArrayList<>());
        }
        for (WorkflowToolStep step : steps) {
            Set<WorkflowToolStep> resolvedDependencies = new LinkedHashSet<>();
            for (String dependency : step.dependencies()) {
                resolvedDependencies.addAll(resolveWorkflowDependency(steps, step, dependency));
            }
            inDegree.put(step, resolvedDependencies.size());
            resolvedDependencies.forEach(dependency -> dependents.get(dependency).add(step));
        }

        Comparator<WorkflowToolStep> stableOrder = Comparator
            .comparingInt(WorkflowToolStep::order)
            .thenComparingInt(WorkflowToolStep::sourceIndex);
        PriorityQueue<WorkflowToolStep> ready = new PriorityQueue<>(stableOrder);
        inDegree.forEach((step, degree) -> {
            if (degree == 0) {
                ready.add(step);
            }
        });
        List<WorkflowToolStep> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            WorkflowToolStep current = ready.remove();
            ordered.add(current);
            for (WorkflowToolStep dependent : dependents.get(current)) {
                int remaining = inDegree.computeIfPresent(dependent, (ignored, degree) -> degree - 1);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() == steps.size()) {
            return ordered;
        }
        List<String> cyclicSteps = steps.stream()
            .filter(step -> inDegree.getOrDefault(step, 0) > 0)
            .sorted(stableOrder)
            .map(this::workflowStepLabel)
            .distinct()
            .toList();
        throw new AgentWorkflowConfigurationException(
            "WORKFLOW_DEPENDENCY_CYCLE",
            "Workflow dependency graph contains a cycle involving: " + cyclicSteps);
    }

    private List<WorkflowToolStep> resolveWorkflowDependency(List<WorkflowToolStep> steps,
                                                             WorkflowToolStep dependent,
                                                             String dependency) {
        String reference = dependency == null ? "" : dependency.trim();
        if (reference.isEmpty()) {
            throw new AgentWorkflowConfigurationException(
                "WORKFLOW_DEPENDENCY_UNRESOLVED",
                "Workflow step '" + workflowStepLabel(dependent) + "' declares a blank dependency");
        }
        List<WorkflowToolStep> matches = steps.stream()
            .filter(candidate -> candidate.aliases().stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(reference)))
            .toList();
        if (matches.isEmpty()) {
            throw new AgentWorkflowConfigurationException(
                "WORKFLOW_DEPENDENCY_UNRESOLVED",
                "Workflow step '" + workflowStepLabel(dependent)
                    + "' depends on unknown step or tool '" + reference + "'");
        }
        Set<Integer> matchedSourceSteps = matches.stream()
            .map(WorkflowToolStep::sourceIndex)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (matchedSourceSteps.size() > 1) {
            throw new AgentWorkflowConfigurationException(
                "WORKFLOW_DEPENDENCY_AMBIGUOUS",
                "Workflow step '" + workflowStepLabel(dependent)
                    + "' dependency '" + reference + "' matches multiple workflow steps "
                    + matchedSourceSteps + "; use a unique id or name");
        }
        if (matchedSourceSteps.contains(dependent.sourceIndex())) {
            throw new AgentWorkflowConfigurationException(
                "WORKFLOW_DEPENDENCY_SELF_REFERENCE",
                "Workflow step '" + workflowStepLabel(dependent) + "' cannot depend on itself via '"
                    + reference + "'");
        }
        return matches;
    }

    private List<String> workflowStepAliases(Map<String, Object> step,
                                             String configuredTool,
                                             String resolvedTool) {
        LinkedHashMap<String, Boolean> aliases = new LinkedHashMap<>();
        for (Object value : new Object[] {
            step.get("id"),
            step.get("name"),
            step.get("step"),
            configuredTool,
            resolvedTool}) {
            String alias = stringValue(value);
            if (alias != null && !alias.isBlank()) {
                aliases.put(alias.trim(), Boolean.TRUE);
            }
        }
        return new ArrayList<>(aliases.keySet());
    }

    private String workflowStepLabel(WorkflowToolStep step) {
        return step.aliases().isEmpty() ? step.toolName() : step.aliases().get(0);
    }

    public ToolExecutionDecision resolveToolExecution(String requestedToolName,
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

    public boolean policyAllowsEarlyFinal(Map<String, Object> runtimeAttributes) {
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
    public void recordWorkflowDecision(Map<String, Object> metadata, ToolExecutionDecision decision) {
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

    public List<Map<String, Object>> decisionRecords(List<ToolExecutionDecision> decisions) {
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
        if ("asset_query".equals(semantic) || "asset_discovery".equals(semantic)) {
            return "asset_discovery";
        }
        if ("template_query".equals(semantic) || "template_discovery".equals(semantic)) {
            return "template_discovery";
        }
        if (semantic.endsWith("_asset_query") || "database_asset_search".equals(semantic)) {
            return "asset_discovery";
        }
        if (semantic.endsWith("_template_query") || semantic.endsWith("_template_search")) {
            return "template_discovery";
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
        String leftAlias = normalizeKnownToolAlias(left);
        String rightAlias = normalizeKnownToolAlias(right);
        return left.equals(right)
            || left.equals(rightAlias)
            || leftAlias.equals(right)
            || leftAlias.equals(rightAlias)
            || toolSemanticKey(left).equals(toolSemanticKey(right));
    }

    private String toolSemanticKey(String toolName) {
        return McpToolNamePolicy.workflowSemanticKey(toolName);
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
        int sourceIndex,
        String toolName,
        List<String> aliases,
        List<String> dependencies,
        boolean executable
    ) {
        private WorkflowToolStep {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
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
    List<ToolExecutionDecision> skippedDecisions,
    List<WorkflowDagNode> authoritativeDag
) {
    WorkflowMandatoryResolution(List<String> tools, List<ToolExecutionDecision> skippedDecisions) {
        this(tools, skippedDecisions, List.of());
    }

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

record WorkflowDagNode(
    String id,
    String toolName,
    List<String> dependsOnTools,
    int order,
    int sourceIndex
) {
    WorkflowDagNode {
        dependsOnTools = dependsOnTools == null ? List.of() : List.copyOf(dependsOnTools);
    }
}
