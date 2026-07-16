package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runtime validator for planner-produced InterpretationPlan JSON.
 */
public class InterpretationPlanValidator {

    private static final String HIGH = "high";
    private static final Set<String> RAW_SQL_PARAMETER_KEYS = Set.of(
        "sql",
        "rawsql",
        "raw_sql",
        "statement",
        "query"
    );
    private static final Set<String> RAW_HTTP_PARAMETER_KEYS = Set.of(
        "url",
        "uri",
        "method",
        "headers",
        "body",
        "bodytemplate",
        "body_template",
        "endpointid",
        "endpoint_id",
        "host",
        "hostname",
        "ip",
        "ipaddress",
        "ip_address",
        "address"
    );
    private static final Set<String> RAW_SSH_PARAMETER_KEYS = Set.of(
        "command",
        "rawcommand",
        "raw_command",
        "shell",
        "script",
        "hostid",
        "host_id",
        "host",
        "hostname",
        "ip",
        "ipaddress",
        "ip_address",
        "address"
    );
    private static final Set<String> ACTION_TYPES = Set.of(
        "mcp_tool", "reasoning", "retrieval", "aggregation", "validation", "final_answer"
    );

    /**
     * Validates a plan against the current tool registry.
     *
     * @param plan the interpretation plan
     * @param toolRegistry the tool registry
     * @return validation result
     */
    public ValidationResult validate(InterpretationPlan plan, ToolRegistry toolRegistry) {
        return validate(plan, toolRegistry, Set.of());
    }

    /**
     * Validates a plan against the current tool registry and request-scoped allowed tools.
     *
     * @param plan the interpretation plan
     * @param toolRegistry the tool registry
     * @param availableTools request-scoped tools visible to the agent
     * @return validation result
     */
    public ValidationResult validate(InterpretationPlan plan, ToolRegistry toolRegistry, Set<String> availableTools) {
        ValidationState state = new ValidationState();
        if (plan == null) {
            state.error("plan", "InterpretationPlan is required");
            return state.result(List.of());
        }

        validateRequiredSections(plan, state);
        List<InterpretationPlan.Step> steps = plan.steps();
        if (steps.isEmpty()) {
            state.error("plan.steps", "At least one plan step is required");
            return state.result(List.of());
        }

        Map<Integer, InterpretationPlan.Step> stepsById = validateSteps(plan, toolRegistry, availableTools, state);
        validateFinalAnswer(steps, state);
        validateExecutionPolicy(plan, steps, toolRegistry, state);
        validateStability(plan, stepsById, toolRegistry, availableTools, state);
        validateDependencyContracts(plan, stepsById, state);
        validateEdgeContracts(plan, stepsById, state);
        validateBindings(plan, stepsById, state);
        List<InterpretationPlan.Step> orderedSteps = validateDag(stepsById, state);
        return state.result(orderedSteps);
    }

    private void validateRequiredSections(InterpretationPlan plan, ValidationState state) {
        if (blank(plan.version())) {
            state.error("version", "Plan version is required");
        }
        if (plan.intent() == null) {
            state.error("intent", "Intent is required");
        } else {
            if (blank(plan.intent().type())) {
                state.error("intent.type", "Intent type is required");
            }
            if (blank(plan.intent().goal())) {
                state.error("intent.goal", "Intent goal is required");
            }
        }
        if (plan.context() == null) {
            state.error("context", "Context is required");
        }
        if (plan.plan() == null) {
            state.error("plan", "Plan body is required");
        }
        if (plan.review() == null) {
            state.error("review", "Review is required");
        } else if (plan.review().selfCheck() == null) {
            state.error("review.self_check", "Review self_check is required");
        }
    }

    private Map<Integer, InterpretationPlan.Step> validateSteps(InterpretationPlan plan,
                                                                ToolRegistry toolRegistry,
                                                                Set<String> availableTools,
                                                                ValidationState state) {
        Map<Integer, InterpretationPlan.Step> stepsById = new LinkedHashMap<>();
        Set<Integer> duplicateIds = new LinkedHashSet<>();
        Set<String> denyTools = normalizedSet(plan.executionPolicy() == null ? null : plan.executionPolicy().denyTool());
        Set<String> allowTools = normalizedSet(plan.executionPolicy() == null ? null : plan.executionPolicy().allowTool());

        for (int index = 0; index < plan.steps().size(); index++) {
            InterpretationPlan.Step step = plan.steps().get(index);
            String path = "plan.steps[" + index + "]";
            if (step == null) {
                state.error(path, "Plan step cannot be null");
                continue;
            }
            if (step.id() == null) {
                state.error(path + ".id", "Step id is required");
            } else if (stepsById.containsKey(step.id())) {
                duplicateIds.add(step.id());
            } else {
                stepsById.put(step.id(), step);
            }
            if (blank(step.actionType())) {
                state.error(path + ".action_type", "Step action_type is required");
            }
            if (step.dependsOn() == null) {
                state.error(path + ".depends_on", "Step depends_on is required");
            }
            validateToolStep(plan, step, path, toolRegistry, availableTools, denyTools, allowTools, state);
        }

        for (Integer duplicateId : duplicateIds) {
            state.error("plan.steps.id", "Step id must be unique: " + duplicateId);
        }
        for (InterpretationPlan.Step step : stepsById.values()) {
            if (step.dependsOn() == null) {
                continue;
            }
            for (Integer dependency : step.dependsOn()) {
                if (dependency == null) {
                    state.error("plan.steps[" + step.id() + "].depends_on", "Dependency id cannot be null");
                } else if (step.id() != null && dependency.equals(step.id())) {
                    state.error("plan.steps[" + step.id() + "].depends_on", "Step cannot depend on itself: " + step.id());
                } else if (!stepsById.containsKey(dependency)) {
                    state.error("plan.steps[" + step.id() + "].depends_on", "Dependency step does not exist: " + dependency);
                }
            }
        }
        return stepsById;
    }

    private void validateToolStep(InterpretationPlan plan,
                                  InterpretationPlan.Step step,
                                  String path,
                                  ToolRegistry toolRegistry,
                                  Set<String> availableTools,
                                  Set<String> denyTools,
                                  Set<String> allowTools,
                                  ValidationState state) {
        if (!step.mcpToolAction()) {
            return;
        }
        if (blank(step.toolName())) {
            state.error(path + ".tool_name", "MCP tool step requires tool_name");
            return;
        }
        if (containsTool(denyTools, step.toolName())) {
            state.error(path + ".tool_name", "Tool is denied by execution_policy.deny_tool: " + step.toolName());
        }
        if (!toolExists(step.toolName(), toolRegistry, availableTools)
            && !templatePlaceholderStep(plan, step, availableTools)) {
            state.error(path + ".tool_name", "Tool is not registered or available: " + step.toolName());
        }
        validateToolInput(plan, step, path, toolRegistry, state);
        validateSqlTemplateExecutionContract(plan, step, path, state);
        validateHttpTemplateExecutionContract(step, path, state);
        validateSshTemplateExecutionContract(step, path, state);
        validateTemplateArgumentShape(plan, step, path, state);
        if (isHighRisk(plan, step, toolRegistry) && !containsTool(allowTools, step.toolName())) {
            state.approval(path + ".tool_name", "High-risk tool requires explicit allow_tool approval: " + step.toolName());
        }
    }

    private void validateToolInput(InterpretationPlan plan,
                                   InterpretationPlan.Step step,
                                   String path,
                                   ToolRegistry toolRegistry,
                                   ValidationState state) {
        ToolMetadata metadata = toolMetadata(step.toolName(), toolRegistry);
        if (metadata == null || metadata.getParameters() == null || metadata.getParameters().isEmpty()) {
            return;
        }
        Map<String, Object> input = step.input() == null ? Map.of() : step.input();
        for (ToolParameter parameter : metadata.getParameters()) {
            if (parameter == null || !parameter.isRequired() || blank(parameter.getName())) {
                continue;
            }
            Object value = input.get(parameter.getName());
            if (missingRequiredValue(value)
                && missingRequiredValue(toolCallParameter(input, parameter))
                && !hasBindingForInput(plan, step.id(), parameter.getName())
                && !runtimeBindableRequiredInput(plan, step, parameter.getName())) {
                state.error(
                    path + ".input." + parameter.getName(),
                    "Required tool input is missing for " + step.toolName() + ": " + parameter.getName()
                );
            }
        }
    }

    private void validateSqlTemplateExecutionContract(InterpretationPlan plan,
                                                      InterpretationPlan.Step step,
                                                      String path,
                                                      ValidationState state) {
        if (step == null || !isSqlQueryExecuteTool(step.toolName()) || step.input() == null || step.input().isEmpty()) {
            return;
        }
        Map<String, Object> input = step.input();
        boolean templateMode = hasNonBlank(input, "template", "templateId", "template_id")
            || invocationValue(input, "templateRef", "template_ref", "templateId", "template") != null;
        if (templateMode && hasNonBlank(input, "sql", "rawSql", "raw_sql", "statement", "query")) {
            state.error(path + ".input", "SQL template execution must use a templateId returned by template_query and template parameters only; do not mix top-level raw SQL with templateId.");
        }
        Object executionContext = firstPresent(input, "executionContext", "mcpExecutionContext");
        if (executionContext == null) {
            executionContext = invocationValue(input, "target", "executionContext", "execution_context");
        }
        if (!hasConcreteExecutionContext(executionContext)
            && !hasBindingForInput(plan, step.id(), "executionContext")
            && !hasBindingForInput(plan, step.id(), "mcpExecutionContext")
            && !dependsOnAssetDiscovery(plan, step)
            && !dependsOnBusinessTemplateDiscovery(plan, step)) {
            state.error(path + ".input.executionContext",
                "sql_query_execute requires logical executionContext, for example {assetName, env}, from user context, template routing metadata, sql_metadata_search/table-location evidence, or an observed invocationExample; do not rely on template parameters for datasource routing.");
        }
        Object parameters = firstPresent(input, "parameters", "params");
        if (parameters == null) {
            parameters = invocationValue(input, "arguments", "parameters", "params");
        }
        if (!(parameters instanceof Map<?, ?> map)) {
            if (tableMetadataTemplate(input)
                && !hasBindingForInput(plan, step.id(), "parameters.tableName")
                && !hasBindingForInput(plan, step.id(), "parameters.table_name")) {
                state.error(path + ".input.parameters",
                    "SQL table metadata template requires parameters.tableName from the user request or a sql_metadata_search/table-location result; parameters cannot be omitted.");
            }
            return;
        }
        if (tableMetadataTemplate(input)
            && !hasNonBlank(map, "tableName", "table_name")
            && !hasBindingForInput(plan, step.id(), "parameters.tableName")
            && !hasBindingForInput(plan, step.id(), "parameters.table_name")) {
            state.error(path + ".input.parameters.tableName",
                "SQL table metadata template requires parameters.tableName. Do not call sql_query_execute with empty parameters when the selected template declares required tableName.");
        }
        for (Object key : map.keySet()) {
            String normalized = normalizeRawSqlKey(key);
            if (RAW_SQL_PARAMETER_KEYS.contains(normalized)) {
                state.error(path + ".input.parameters." + key,
                    "Raw SQL is not a template parameter. Select a template from database_ops_template_search and pass only fields declared by templates[].parameterSchema.");
            }
        }
    }

    private boolean tableMetadataTemplate(Map<String, Object> input) {
        Object value = firstPresent(input, "templateId", "template", "template_id");
        if (value == null || String.valueOf(value).isBlank()) {
            return false;
        }
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        return normalized.endsWith("_TABLE_METADATA");
    }

    private void validateHttpTemplateExecutionContract(InterpretationPlan.Step step,
                                                       String path,
                                                       ValidationState state) {
        if (step == null || !isHttpRequestExecuteTool(step.toolName()) || step.input() == null || step.input().isEmpty()) {
            return;
        }
        Map<String, Object> input = step.input();
        if (hasRawKeys(input, RAW_HTTP_PARAMETER_KEYS)) {
            state.error(path + ".input",
                "HTTP template execution must use a returned template id plus parameters only; do not pass raw url, uri, method, headers, body, endpointId, host, or IP fields.");
        }
        Object parameters = firstPresent(input, "parameters", "params");
        if (parameters instanceof Map<?, ?> map && hasRawKeys(map, RAW_HTTP_PARAMETER_KEYS)) {
            state.error(path + ".input.parameters",
                "Raw HTTP request fields are not template parameters. Select an HTTP/API template and pass only fields declared by templates[].parameterSchema.");
        }
    }

    private void validateSshTemplateExecutionContract(InterpretationPlan.Step step,
                                                      String path,
                                                      ValidationState state) {
        if (step == null || !isLinuxCommandExecuteTool(step.toolName()) || step.input() == null || step.input().isEmpty()) {
            return;
        }
        Map<String, Object> input = step.input();
        if (hasRawKeys(input, RAW_SSH_PARAMETER_KEYS)) {
            state.error(path + ".input",
                "SSH template execution must use a returned template id plus parameters only; do not pass command, rawCommand, shell, hostId, host, hostname, IP, or address fields.");
        }
        Object parameters = firstPresent(input, "parameters", "params");
        if (parameters instanceof Map<?, ?> map && hasRawKeys(map, RAW_SSH_PARAMETER_KEYS)) {
            state.error(path + ".input.parameters",
                "Raw SSH command/target fields are not template parameters. Select an SSH template and pass only fields declared by templates[].parameterSchema.");
        }
    }

    private void validateTemplateArgumentShape(InterpretationPlan plan,
                                               InterpretationPlan.Step step,
                                               String path,
                                               ValidationState state) {
        if (step == null || step.input() == null
            || (!isSqlQueryExecuteTool(step.toolName())
            && !isHttpRequestExecuteTool(step.toolName())
            && !isLinuxCommandExecuteTool(step.toolName()))) {
            return;
        }
        Object template = firstPresent(step.input(), "templateId", "template", "template_id");
        boolean templateBound = hasBindingForInput(plan, step.id(), "templateId")
            || hasBindingForInput(plan, step.id(), "template")
            || hasBindingForInput(plan, step.id(), "template_id");
        if (template != null && (!(template instanceof CharSequence) || String.valueOf(template).isBlank())
            && !templateBound) {
            state.error(path + ".input.templateId",
                "template/templateId must be one scalar string. Do not pass templates[i], selectedTemplate, or another object as the template id.");
        }
        Object parameters = firstPresent(step.input(), "parameters", "params");
        if (parameters != null && !(parameters instanceof Map<?, ?>)) {
            state.error(path + ".input.parameters",
                "parameters must be an object containing execution values only.");
        } else if (parameters instanceof Map<?, ?> map
            && (map.containsKey("properties") || map.containsKey("$schema")) && map.containsKey("type")) {
            state.error(path + ".input.parameters",
                "parameterSchema is read-only discovery metadata; construct parameter values from it instead of passing the schema to the executor.");
        }
        Object context = firstPresent(step.input(), "executionContext", "mcpExecutionContext");
        if (context != null && !(context instanceof Map<?, ?>)) {
            state.error(path + ".input.executionContext", "executionContext must be an object.");
        }
    }

    private boolean hasRawKeys(Map<?, ?> input, Set<String> forbidden) {
        if (input == null || input.isEmpty() || forbidden == null || forbidden.isEmpty()) {
            return false;
        }
        return input.keySet().stream()
            .map(this::normalizeRawSqlKey)
            .anyMatch(forbidden::contains);
    }

    private Object invocationValue(Map<String, Object> input, String... keys) {
        Object toolCallValue = firstPresent(input, "toolCall", "tool_call");
        if (toolCallValue instanceof Map<?, ?> toolCall) {
            for (String key : keys) {
                if (toolCall.containsKey(key)) {
                    return toolCall.get(key);
                }
                if (Set.of("templateRef", "template_ref", "templateId", "template").contains(key)
                    && toolCall.containsKey("action")) {
                    return toolCall.get("action");
                }
                if (Set.of("arguments", "parameters", "params").contains(key)
                    && toolCall.containsKey("parameters")) {
                    return toolCall.get("parameters");
                }
                if (Set.of("target", "executionContext", "execution_context").contains(key)
                    && toolCall.get("context") instanceof Map<?, ?> context && context.containsKey("target")) {
                    return context.get("target");
                }
            }
        }
        Object envelope = firstPresent(input, "invocation", "modelInvocation", "model_invocation");
        if (!(envelope instanceof Map<?, ?> map)) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private Object toolCallParameter(Map<String, Object> input, ToolParameter parameter) {
        String parameterName = parameter == null ? null : parameter.getName();
        if (input == null || parameterName == null) {
            return null;
        }
        Object value = firstPresent(input, "toolCall", "tool_call");
        if (!(value instanceof Map<?, ?> toolCall)) {
            return null;
        }
        if ("action".equalsIgnoreCase(parameterName) && toolCall.containsKey("action")) {
            return toolCall.get("action");
        }
        Object parameters = toolCall.get("parameters");
        if (!(parameters instanceof Map<?, ?> map)) {
            return null;
        }
        Set<String> expected = new LinkedHashSet<>();
        expected.add(normalizeField(parameterName));
        if (parameter.getMetadata() != null) {
            for (String key : List.of("aliases", "acceptedSources")) {
                Object aliases = parameter.getMetadata().get(key);
                if (aliases instanceof Iterable<?> iterable) {
                    for (Object alias : iterable) {
                        expected.add(normalizeField(String.valueOf(alias)));
                    }
                }
            }
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (expected.contains(normalizeField(String.valueOf(entry.getKey())))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isSqlQueryExecuteTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("sql_query_execute")
            || normalized.contains("_sql_query_execute")
            || normalized.endsWith("sql_script_execute")
            || normalized.contains("_sql_script_execute")
            || normalized.endsWith("database_execute")
            || normalized.endsWith("sql_execute");
    }

    private boolean isHttpRequestExecuteTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("http_request_execute")
            || normalized.contains("_http_request_execute");
    }

    private boolean isLinuxCommandExecuteTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("linux_command_execute")
            || normalized.contains("_linux_command_execute");
    }

    private boolean hasNonBlank(Map<?, ?> input, String... keys) {
        if (input == null || input.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            Object value = input.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Object firstPresent(Map<String, Object> input, String... keys) {
        if (input == null || input.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (input.containsKey(key)) {
                return input.get(key);
            }
        }
        return null;
    }

    private String normalizeRawSqlKey(Object key) {
        return key == null ? "" : String.valueOf(key).trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasBindingForInput(InterpretationPlan plan, Integer stepId, String inputField) {
        if (plan == null || plan.plan() == null || plan.plan().bindings() == null
            || stepId == null || blank(inputField)) {
            return false;
        }
        return plan.plan().bindings().stream()
            .filter(binding -> binding != null && stepId.equals(binding.to()))
            .map(InterpretationPlan.Binding::inputField)
            .filter(field -> field != null && !field.isBlank())
            .anyMatch(field -> inputField.equals(field) || inputField.equals(field.split("\\.")[0]));
    }

    private boolean hasConcreteExecutionContext(Object executionContext) {
        if (!(executionContext instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        boolean hasAsset = hasConcreteValue(map, "assetName", "asset_name", "name");
        boolean hasEnv = hasConcreteValue(map, "env", "environment");
        return hasAsset && hasEnv;
    }

    private boolean hasConcreteValue(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank() && !isJsonPathPlaceholder(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJsonPathPlaceholder(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("$.") || trimmed.startsWith("$[");
    }

    private boolean dependsOnAssetDiscovery(InterpretationPlan plan, InterpretationPlan.Step step) {
        if (plan == null || step == null || step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return false;
        }
        return plan.steps().stream()
            .filter(candidate -> candidate != null && step.dependsOn().contains(candidate.id()))
            .anyMatch(candidate -> isAssetDiscoveryTool(candidate.toolName()));
    }

    private boolean dependsOnBusinessTemplateDiscovery(InterpretationPlan plan, InterpretationPlan.Step step) {
        if (plan == null || step == null || step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return false;
        }
        return plan.steps().stream()
            .filter(candidate -> candidate != null && step.dependsOn().contains(candidate.id()))
            .anyMatch(candidate -> isBusinessTemplateDiscoveryTool(candidate.toolName()));
    }

    private boolean isBusinessTemplateDiscoveryTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.endsWith("business_query_template_search")
            || normalized.endsWith("database_query_template_query");
    }

    private boolean missingRequiredValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    private void validateFinalAnswer(List<InterpretationPlan.Step> steps, ValidationState state) {
        long finalAnswerCount = steps.stream()
            .filter(step -> step != null && step.finalAnswerAction())
            .count();
        if (finalAnswerCount != 1) {
            state.error("plan.steps", "Exactly one final_answer step is required, found: " + finalAnswerCount);
        }
    }

    private void validateExecutionPolicy(InterpretationPlan plan,
                                         List<InterpretationPlan.Step> steps,
                                         ToolRegistry toolRegistry,
                                         ValidationState state) {
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        if (policy == null) {
            return;
        }
        if (policy.maxSteps() != null && steps.size() > policy.maxSteps()) {
            state.error("execution_policy.max_steps", "Plan has more steps than max_steps: " + steps.size());
        }
        if (policy.timeoutMs() != null && policy.timeoutMs() <= 0) {
            state.error("execution_policy.timeout_ms", "timeout_ms must be positive when provided");
        }
        if (policy.maxRewriteTimes() != null && policy.maxRewriteTimes() < 0) {
            state.error("execution_policy.max_rewrite_times", "max_rewrite_times must be zero or positive");
        }
        if (policy.fallbackMode() != null
            && !policy.fallbackMode().isBlank()
            && !"safe_answer".equals(policy.fallbackMode())
            && !"partial_result".equals(policy.fallbackMode())) {
            state.error("execution_policy.fallback_mode", "fallback_mode must be safe_answer or partial_result");
        }
        if (policy.costBudget() != null && policy.costBudget() < 0) {
            state.error("execution_policy.cost_budget", "cost_budget must be zero or positive");
        }
        if (policy.latencyBudgetMs() != null && policy.latencyBudgetMs() <= 0) {
            state.error("execution_policy.latency_budget_ms", "latency_budget_ms must be positive");
        }
        if (policy.accuracyVsSpeed() != null && (policy.accuracyVsSpeed() < 0 || policy.accuracyVsSpeed() > 1)) {
            state.error("execution_policy.accuracy_vs_speed", "accuracy_vs_speed must be between 0 and 1");
        }
        if (policy.toolPriority() != null) {
            for (Map.Entry<String, Double> entry : policy.toolPriority().entrySet()) {
                if (blank(entry.getKey())) {
                    state.error("execution_policy.tool_priority", "tool_priority tool name cannot be blank");
                    continue;
                }
                if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 1) {
                    state.error("execution_policy.tool_priority." + entry.getKey(), "tool priority must be between 0 and 1");
                }
                if (toolRegistry != null && !toolExists(entry.getKey(), toolRegistry, Set.of())) {
                    state.warning("execution_policy.tool_priority." + entry.getKey(), "Priority references an unregistered tool: " + entry.getKey());
                }
            }
        }
        validateKnownToolNames("execution_policy.allow_tool", policy.allowTool(), toolRegistry, state);
        validateKnownToolNames("execution_policy.deny_tool", policy.denyTool(), toolRegistry, state);
    }

    private void validateStability(InterpretationPlan plan,
                                   Map<Integer, InterpretationPlan.Step> stepsById,
                                   ToolRegistry toolRegistry,
                                   Set<String> availableTools,
                                   ValidationState state) {
        InterpretationPlan.Stability stability = plan == null || plan.plan() == null ? null : plan.plan().stability();
        if (stability == null) {
            return;
        }
        if (stability.stableNodes() != null) {
            for (Integer stepId : stability.stableNodes()) {
                if (stepId == null || !stepsById.containsKey(stepId)) {
                    state.error("plan.stability.stable_nodes", "Stable node does not exist: " + stepId);
                }
            }
        }
        if (stability.criticalTools() != null) {
            for (String toolName : stability.criticalTools()) {
                if (blank(toolName)) {
                    state.error("plan.stability.critical_tools", "Critical tool name cannot be blank");
                } else if (!toolExists(toolName, toolRegistry, availableTools)) {
                    state.warning("plan.stability.critical_tools", "Critical tool is not registered or available: " + toolName);
                }
            }
        }
        if (stability.mutableActionTypes() != null) {
            for (String actionType : stability.mutableActionTypes()) {
                if (!ACTION_TYPES.contains(normalize(actionType))) {
                    state.error("plan.stability.mutable_action_types", "Unsupported mutable action_type: " + actionType);
                }
            }
        }
    }

    private void validateEdgeContracts(InterpretationPlan plan,
                                       Map<Integer, InterpretationPlan.Step> stepsById,
                                       ValidationState state) {
        if (plan == null || plan.plan() == null || plan.plan().edgeContracts() == null) {
            return;
        }
        for (int index = 0; index < plan.plan().edgeContracts().size(); index++) {
            InterpretationPlan.EdgeContract contract = plan.plan().edgeContracts().get(index);
            String path = "plan.edge_contracts[" + index + "]";
            if (contract == null) {
                state.error(path, "Edge contract cannot be null");
                continue;
            }
            if (contract.from() == null || !stepsById.containsKey(contract.from())) {
                state.error(path + ".from", "Edge contract source step does not exist: " + contract.from());
            }
            if (contract.to() == null || !stepsById.containsKey(contract.to())) {
                state.error(path + ".to", "Edge contract target step does not exist: " + contract.to());
            }
            if (blank(contract.field())) {
                state.error(path + ".field", "Edge contract field is required");
            }
            if (blank(contract.type())) {
                state.error(path + ".type", "Edge contract type is required");
            } else if (!Set.of("array", "object", "string", "number", "boolean", "any").contains(normalize(contract.type()))) {
                state.error(path + ".type", "Unsupported edge contract type: " + contract.type());
            }
            InterpretationPlan.Step target = stepsById.get(contract.to());
            if (target != null
                && contract.from() != null
                && (target.dependsOn() == null || !target.dependsOn().contains(contract.from()))) {
                state.warning(path, "Edge contract target should depend on source step: " + contract.from() + " -> " + contract.to());
            }
        }
    }

    private void validateDependencyContracts(InterpretationPlan plan,
                                             Map<Integer, InterpretationPlan.Step> stepsById,
                                             ValidationState state) {
        if (plan == null || plan.plan() == null || plan.plan().dependencyContracts() == null) {
            return;
        }
        for (int index = 0; index < plan.plan().dependencyContracts().size(); index++) {
            InterpretationPlan.DependencyContract contract = plan.plan().dependencyContracts().get(index);
            String path = "plan.dependency_contracts[" + index + "]";
            if (contract == null) {
                state.error(path, "Dependency contract cannot be null");
                continue;
            }
            if (contract.from() == null || !stepsById.containsKey(contract.from())) {
                state.error(path + ".from", "Dependency contract source step does not exist: " + contract.from());
            }
            if (contract.to() == null || !stepsById.containsKey(contract.to())) {
                state.error(path + ".to", "Dependency contract target step does not exist: " + contract.to());
            }
            boolean required = contract.required() == null || contract.required();
            InterpretationPlan.Step target = stepsById.get(contract.to());
            if (required
                && target != null
                && contract.from() != null
                && (target.dependsOn() == null || !target.dependsOn().contains(contract.from()))) {
                state.error(path + ".required",
                    "Required dependency contract must also appear in target depends_on: " + contract.from() + " -> " + contract.to());
            }
            if (!required && blank(contract.condition()) && blank(contract.reason())) {
                state.error(path + ".condition",
                    "Optional dependency contract requires condition or reason so planner can decide when to include it.");
            }
            if (!blank(contract.onFailure())
                && !Set.of("stop", "skip", "continue_with_partial_evidence", "replan").contains(normalize(contract.onFailure()))) {
                state.error(path + ".on_failure", "Unsupported dependency failure policy: " + contract.onFailure());
            }
        }
    }

    private void validateBindings(InterpretationPlan plan,
                                  Map<Integer, InterpretationPlan.Step> stepsById,
                                  ValidationState state) {
        if (plan == null || plan.plan() == null || plan.plan().bindings() == null) {
            return;
        }
        for (int index = 0; index < plan.plan().bindings().size(); index++) {
            InterpretationPlan.Binding binding = plan.plan().bindings().get(index);
            String path = "plan.bindings[" + index + "]";
            if (binding == null) {
                state.error(path, "Binding cannot be null");
                continue;
            }
            if (binding.from() == null || !stepsById.containsKey(binding.from())) {
                state.error(path + ".from", "Binding source step does not exist: " + binding.from());
            }
            if (binding.to() == null || !stepsById.containsKey(binding.to())) {
                state.error(path + ".to", "Binding target step does not exist: " + binding.to());
            }
            if (blank(binding.outputPath())) {
                state.error(path + ".output_path", "Binding output_path is required");
            }
            if (blank(binding.inputField())) {
                state.error(path + ".input_field", "Binding input_field is required");
            }
            String type = normalize(binding.type());
            if (type.isBlank()) {
                state.error(path + ".type", "Binding type is required");
            } else if (!Set.of("jsonpath", "jq").contains(type)) {
                state.error(path + ".type", "Unsupported binding type: " + binding.type());
            }
            InterpretationPlan.Step target = stepsById.get(binding.to());
            InterpretationPlan.Step source = stepsById.get(binding.from());
            if ((binding.required() == null || binding.required())
                && lockedEdges(plan)
                && !hasMatchingEdgeContract(plan, binding)) {
                state.error(path,
                    "Required binding on a locked-edge plan must have a matching edge_contract with the same from, to, and canonical source field.");
            }
            if (target != null
                && templateExecutionTool(target.toolName())
                && targetsExecutionParameters(binding.inputField())
                && schemaMetadataOutputPath(binding.outputPath())) {
                state.error(path,
                    "Template parameterSchema/requiredParameters/parameterContract/invocationExample are read-only discovery metadata and must not be bound to executor parameters. Bind concrete parameter values only, or keep parameters={} when the selected template declares no required parameters.");
            }
            if (target != null
                && source != null
                && isAssetDiscoveryTool(source.toolName())
                && targetsAssetName(binding.inputField())
                && !canonicalAssetNameOutputPath(binding.outputPath())) {
                state.error(path + ".output_path",
                    "Asset-name bindings must use the canonical asset discovery path $.assets[0].asset.name.");
            }
            if (target != null
                && (isSqlQueryExecuteTool(target.toolName())
                || isHttpRequestExecuteTool(target.toolName())
                || isLinuxCommandExecuteTool(target.toolName()))
                && (sameField(binding.inputField(), "template")
                || sameField(binding.inputField(), "templateId")
                || sameField(binding.inputField(), "template_id"))
                && !templateIdentifierLeaf(binding.outputPath())) {
                state.warning(path + ".output_path",
                    "Template-id bindings should select the scalar templates[i].templateId leaf. Runtime will project a recognized template object, but objects are never sent to MCP executors.");
            }
            if (target != null
                && source != null
                && isSqlQueryExecuteTool(target.toolName())
                && isAssetDiscoveryTool(source.toolName())
                && sameField(binding.inputField(), "parameters.schemaName")
                && containsNormalized(binding.outputPath(), "asset.name")) {
                state.error(path + ".input_field",
                    "Do not bind asset_query assets[].asset.name into sql_query_execute parameters.schemaName. Asset name is routing context, not database/schema name; use sql_metadata_search results or omit schemaName when the selected template does not require it.");
            }
            if (target != null
                && binding.from() != null
                && (target.dependsOn() == null || !target.dependsOn().contains(binding.from()))) {
                state.warning(path, "Binding target should depend on source step: " + binding.from() + " -> " + binding.to());
            }
        }
    }

    private boolean isAssetDiscoveryTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("_asset_query")
            || normalized.endsWith("_asset_search")
            || "asset_query".equals(normalized)
            || "asset_search".equals(normalized);
    }

    private boolean targetsExecutionParameters(String inputField) {
        String normalized = normalizeField(inputField);
        return "parameters".equals(normalized) || "params".equals(normalized);
    }

    private boolean lockedEdges(InterpretationPlan plan) {
        return plan != null && plan.plan() != null && plan.plan().stability() != null
            && Boolean.TRUE.equals(plan.plan().stability().lockedEdges());
    }

    private boolean hasMatchingEdgeContract(InterpretationPlan plan, InterpretationPlan.Binding binding) {
        if (plan == null || plan.plan() == null || plan.plan().edgeContracts() == null || binding == null) {
            return false;
        }
        String bindingField = normalizeField(binding.outputPath());
        return plan.plan().edgeContracts().stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(contract -> java.util.Objects.equals(contract.from(), binding.from())
                && java.util.Objects.equals(contract.to(), binding.to())
                && normalizeField(contract.field()).equals(bindingField));
    }

    private boolean targetsAssetName(String inputField) {
        String normalized = normalizeField(inputField);
        return normalized.endsWith("assetname");
    }

    private boolean schemaMetadataOutputPath(String outputPath) {
        String normalized = normalizeField(outputPath);
        return normalized.contains("parameterschema")
            || normalized.contains("requiredparameters")
            || normalized.contains("parametercontract")
            || normalized.contains("invocationexample");
    }

    private boolean canonicalAssetNameOutputPath(String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return false;
        }
        String normalized = outputPath.trim().replace("['", ".").replace("']", "")
            .replace("[\"", ".").replace("\"]", "").toLowerCase(Locale.ROOT);
        return normalized.endsWith("assets[0].asset.name");
    }

    private boolean sameField(String value, String expected) {
        return normalizeField(value).equals(normalizeField(expected));
    }

    private boolean templateIdentifierLeaf(String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return false;
        }
        String normalized = outputPath.trim().replace("['", ".").replace("']", "")
            .replace("[\"", ".").replace("\"]", "").toLowerCase(Locale.ROOT);
        return normalized.endsWith(".templateid") || normalized.endsWith(".template_id")
            || normalized.endsWith(".id") || normalized.endsWith(".code")
            || normalized.equals("$.templateid") || normalized.equals("$.id") || normalized.equals("$.code");
    }

    private boolean containsNormalized(String value, String token) {
        return normalizeField(value).contains(normalizeField(token));
    }

    private String normalizeField(String value) {
        return value == null ? "" : value.replace("_", "").replace("-", "").replace("$", "")
            .replace("[", "").replace("]", "").replace(".", "").trim().toLowerCase(Locale.ROOT);
    }

    private void validateKnownToolNames(String path,
                                        List<String> toolNames,
                                        ToolRegistry toolRegistry,
                                        ValidationState state) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        for (String toolName : toolNames) {
            if (blank(toolName)) {
                state.error(path, "Tool name cannot be blank");
            } else if (toolRegistry != null && !toolExists(toolName, toolRegistry, Set.of())) {
                state.warning(path, "Tool is not currently registered: " + toolName);
            }
        }
    }

    private List<InterpretationPlan.Step> validateDag(Map<Integer, InterpretationPlan.Step> stepsById, ValidationState state) {
        if (stepsById.isEmpty()) {
            return List.of();
        }
        Map<Integer, VisitState> visitStates = new HashMap<>();
        List<InterpretationPlan.Step> ordered = new ArrayList<>();
        for (Integer id : stepsById.keySet()) {
            visitStep(id, stepsById, visitStates, ordered, state);
        }
        return state.hasErrors() ? List.of() : ordered;
    }

    private void visitStep(Integer stepId,
                           Map<Integer, InterpretationPlan.Step> stepsById,
                           Map<Integer, VisitState> visitStates,
                           List<InterpretationPlan.Step> ordered,
                           ValidationState state) {
        VisitState visitState = visitStates.get(stepId);
        if (visitState == VisitState.DONE) {
            return;
        }
        if (visitState == VisitState.VISITING) {
            state.error("plan.steps.depends_on", "Plan must be a DAG; cycle detected at step: " + stepId);
            return;
        }
        InterpretationPlan.Step step = stepsById.get(stepId);
        if (step == null) {
            return;
        }
        visitStates.put(stepId, VisitState.VISITING);
        if (step.dependsOn() != null) {
            for (Integer dependency : step.dependsOn()) {
                if (stepsById.containsKey(dependency)) {
                    visitStep(dependency, stepsById, visitStates, ordered, state);
                }
            }
        }
        visitStates.put(stepId, VisitState.DONE);
        ordered.add(step);
    }

    private boolean isHighRisk(InterpretationPlan plan,
                               InterpretationPlan.Step step,
                               ToolRegistry toolRegistry) {
        if (plan.intent() != null && HIGH.equals(normalize(plan.intent().riskLevel()))) {
            return true;
        }
        ToolMetadata metadata = toolMetadata(step.toolName(), toolRegistry);
        return metadata != null && HIGH.equals(normalize(metadata.getRiskLevel()));
    }

    private boolean toolExists(String toolName, ToolRegistry toolRegistry, Set<String> availableTools) {
        if (availableTools != null && !availableTools.isEmpty()) {
            return containsTool(availableTools, toolName);
        }
        if (toolRegistry == null || blank(toolName)) {
            return false;
        }
        try {
            if (toolRegistry.hasTool(toolName)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Fall through to metadata-based resolution.
        }
        return toolMetadata(toolName, toolRegistry) != null;
    }

    private boolean templatePlaceholderStep(InterpretationPlan plan,
                                            InterpretationPlan.Step step,
                                            Set<String> availableTools) {
        if (plan == null || plan.plan() == null || step == null || step.dependsOn() == null
            || step.dependsOn().isEmpty() || availableTools == null || availableTools.isEmpty()) {
            return false;
        }
        return dependsOnTemplateDiscovery(plan, step) && availableTools.stream().anyMatch(this::templateExecutionTool);
    }

    private boolean runtimeBindableRequiredInput(InterpretationPlan plan,
                                                 InterpretationPlan.Step step,
                                                 String parameterName) {
        if (plan == null || step == null || blank(parameterName) || !templateExecutionTool(step.toolName())) {
            return false;
        }
        String key = parameterName.replace("_", "").replace("-", "").trim().toLowerCase(Locale.ROOT);
        if (!"template".equals(key) && !"templateid".equals(key) && !"templatecode".equals(key)) {
            return invocationValue(step.input(), "arguments", "parameters", "params", "target", "executionContext") != null;
        }
        return dependsOnTemplateDiscovery(plan, step)
            || invocationValue(step.input(), "templateRef", "template_ref", "templateId", "template") != null;
    }

    private boolean dependsOnTemplateDiscovery(InterpretationPlan plan, InterpretationPlan.Step step) {
        if (plan == null || step == null || step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return false;
        }
        return plan.steps().stream()
            .filter(candidate -> candidate != null && candidate.id() != null && step.dependsOn().contains(candidate.id()))
            .anyMatch(candidate -> candidate.mcpToolAction() && templateDiscoveryTool(candidate.toolName()));
    }

    private boolean templateDiscoveryTool(String toolName) {
        String semantic = semanticToolName(toolName);
        return "template_query".equals(semantic)
            || semantic.endsWith("_template_query")
            || semantic.endsWith("_template_search");
    }

    private boolean templateExecutionTool(String toolName) {
        String semantic = semanticToolName(toolName);
        return "sql_query_execute".equals(semantic)
            || semantic.endsWith("_sql_query_execute")
            || "sql_script_execute".equals(semantic)
            || semantic.endsWith("_sql_script_execute")
            || "database_query_execute".equals(semantic)
            || semantic.endsWith("_database_query_execute")
            || "linux_command_execute".equals(semantic)
            || semantic.endsWith("_linux_command_execute")
            || "http_request_execute".equals(semantic)
            || semantic.endsWith("_http_request_execute")
            || "api_template_execute".equals(semantic)
            || semantic.endsWith("_api_template_execute");
    }

    private String semanticToolName(String toolName) {
        String normalized = normalize(toolName);
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        for (String prefix : List.of("chatchat_mcp_server_", "chatchat_", "xxx_")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
            }
        }
        return normalized;
    }

    private ToolMetadata toolMetadata(String toolName, ToolRegistry toolRegistry) {
        if (toolRegistry == null || blank(toolName)) {
            return null;
        }
        try {
            return toolRegistry.getToolMetadata(toolName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Set<String> normalizedSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            String key = normalize(value);
            if (!key.isBlank()) {
                normalized.add(key);
            }
        }
        return normalized;
    }

    private boolean containsTool(Set<String> toolNames, String toolName) {
        if (toolNames == null || toolNames.isEmpty() || blank(toolName)) {
            return false;
        }
        return toolNames.contains(normalize(toolName));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private enum VisitState {
        VISITING,
        DONE
    }

    private static final class ValidationState {
        private final List<ValidationIssue> errors = new ArrayList<>();
        private final List<ValidationIssue> warnings = new ArrayList<>();
        private final List<ValidationIssue> approvalRequests = new ArrayList<>();

        private void error(String path, String message) {
            errors.add(new ValidationIssue("error", path, message));
        }

        private void warning(String path, String message) {
            warnings.add(new ValidationIssue("warning", path, message));
        }

        private void approval(String path, String message) {
            approvalRequests.add(new ValidationIssue("approval_required", path, message));
        }

        private boolean hasErrors() {
            return !errors.isEmpty();
        }

        private ValidationResult result(List<InterpretationPlan.Step> orderedSteps) {
            return new ValidationResult(
                errors.isEmpty(),
                errors.isEmpty() && approvalRequests.isEmpty(),
                !approvalRequests.isEmpty(),
                List.copyOf(errors),
                List.copyOf(warnings),
                List.copyOf(approvalRequests),
                orderedSteps == null ? List.of() : List.copyOf(orderedSteps)
            );
        }
    }

    public record ValidationIssue(
        String severity,
        String path,
        String message
    ) {
    }

    public record ValidationResult(
        boolean valid,
        boolean executable,
        boolean approvalRequired,
        List<ValidationIssue> errors,
        List<ValidationIssue> warnings,
        List<ValidationIssue> approvalRequests,
        List<InterpretationPlan.Step> orderedSteps
    ) {
        public List<ValidationIssue> issues() {
            List<ValidationIssue> issues = new ArrayList<>();
            issues.addAll(errors == null ? List.of() : errors);
            issues.addAll(warnings == null ? List.of() : warnings);
            issues.addAll(approvalRequests == null ? List.of() : approvalRequests);
            return Collections.unmodifiableList(issues);
        }
    }
}
