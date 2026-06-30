package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites failed InterpretationPlans into a new validated plan.
 */
@Slf4j
public class InterpretationPlanRewriter {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final InterpretationPlanValidator validator;

    public InterpretationPlanRewriter(ChatModel chatModel,
                                      ObjectMapper objectMapper,
                                      InterpretationPlanValidator validator) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.validator = validator == null ? new InterpretationPlanValidator() : validator;
    }

    /**
     * Rewrites a failed plan. The returned plan is not executed by this class.
     *
     * @param request the rewrite request
     * @return the rewrite result
     */
    public RewriteResult rewrite(RewriteRequest request) {
        if (request == null || request.originalPlan() == null) {
            return RewriteResult.failed("Rewrite request and original plan are required", null, null);
        }
        if (chatModel == null) {
            return RewriteResult.failed("ChatModel is required for plan rewriting", null, null);
        }
        String prompt = buildRewritePrompt(request);
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=interpretation_plan_rewrite promptChars={} observationCount={} availableToolCount={}",
            prompt.length(),
            request.observations() == null ? 0 : request.observations().size(),
            request.availableTools() == null ? 0 : request.availableTools().size());
        String raw = chatModel.chat(prompt);
        log.info("agentModelResponse phase=interpretation_plan_rewrite durationMs={} responseChars={}",
            System.currentTimeMillis() - startedAt,
            raw == null ? 0 : raw.length());
        log.info("agentModelRawOutput phase=interpretation_plan_rewrite raw=\n{}",
            ModelProtocolJson.prettyJsonForLog(raw));
        try {
            InterpretationPlan rewrittenPlan = objectMapper.readValue(extractJson(raw), InterpretationPlan.class);
            InterpretationPlanValidator.ValidationResult validation = validator.validate(
                rewrittenPlan,
                request.toolRegistry(),
                new java.util.LinkedHashSet<>(request.availableTools() == null ? List.of() : request.availableTools())
            );
            if (!validation.valid()) {
                InterpretationPlan repairedPlan = repairContinuationPlan(request.originalPlan(), rewrittenPlan);
                repairedPlan = repairExecutionPolicyStepLimit(repairedPlan);
                if (repairedPlan != rewrittenPlan) {
                    InterpretationPlanValidator.ValidationResult repairedValidation = validator.validate(
                        repairedPlan,
                        request.toolRegistry(),
                        new java.util.LinkedHashSet<>(request.availableTools() == null ? List.of() : request.availableTools())
                    );
                    if (repairedValidation.valid()) {
                        log.info("InterpretationPlan rewrite repaired as continuation DAG. originalErrors={}, repairedStepCount={}",
                            validationSummary(validation),
                            repairedPlan.steps().size());
                        return new RewriteResult(true, repairedValidation.executable(), repairedPlan, repairedValidation, raw, null);
                    }
                    return new RewriteResult(false, false, repairedPlan, repairedValidation, raw,
                        "Rewritten plan failed validation after continuation repair: " + validationSummary(repairedValidation));
                }
                return new RewriteResult(false, false, rewrittenPlan, validation, raw,
                    "Rewritten plan failed validation: " + validationSummary(validation));
            }
            return new RewriteResult(true, validation.executable(), rewrittenPlan, validation, raw, null);
        } catch (Exception ex) {
            log.debug("Failed to parse rewritten InterpretationPlan: {}", raw, ex);
            return RewriteResult.failed("Failed to parse rewritten InterpretationPlan: " + ex.getMessage(), raw, null);
        }
    }

    private String buildRewritePrompt(RewriteRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an MCP plan rewriter.\n");
        prompt.append("Your job is to repair a failed InterpretationPlan without executing tools.\n");
        prompt.append("Output exactly one valid InterpretationPlan JSON object. No markdown, comments, code fences, or natural language.\n");
        prompt.append("Rewrite rules:\n");
        prompt.append("- Preserve the user's original goal and already successful evidence.\n");
        prompt.append("- Treat completed steps and evidence_execution_lock_v1 observations as immutable execution state; do not re-add them as runnable tool steps.\n");
        prompt.append("- Do not repeat a failed MCP tool step unless the failure reason is transient and no safer alternative exists.\n");
        prompt.append("- Remove impossible dependencies and keep the result as a DAG.\n");
        prompt.append("- Use only available tools. If no safe tool remains, produce a final_answer step explaining what is missing.\n");
        prompt.append("- Include exactly one final_answer step and keep every depends_on id valid.\n");
        prompt.append("- Preserve or tighten execution_policy.max_rewrite_times and fallback_mode.\n");
        prompt.append("- Preserve execution_policy tool priority/cost/latency/accuracy constraints unless the failure proves they are impossible.\n");
        prompt.append("- Preserve plan.stability stable_nodes, critical_tools, and locked_edges; do not alter locked edges.\n");
        prompt.append("- Add or update plan.edge_contracts when the failure was caused by missing or mistyped tool output fields.\n");
        prompt.append("- Keep execution_policy.deny_tool for tools that failed due to policy, permission, or safety.\n\n");
        boolean templateDiscoveryAvailable = hasAvailableSemanticTool(request.availableTools(), "template_discovery");
        boolean metadataSearchAvailable = hasAvailableSemanticTool(request.availableTools(), "sql_metadata_search");
        prompt.append("SQL template repair rules:\n");
        if (templateDiscoveryAvailable) {
            prompt.append("- If a failed sql_query_execute step used input.parameters.sql/rawSql/query/statement, remove that raw SQL parameter and replan through the available sql datasource template_query tool.\n");
            prompt.append("- Bind a returned templates[].templateId into sql_query_execute.templateId and pass only parameters declared by templates[].parameterSchema.\n");
        } else {
            prompt.append("- No template discovery tool is available in this rewrite request. Do not add sql_datasource_template_query/template_query steps. Remove raw SQL fields, then call sql_query_execute only when a concrete templateId and parameter contract already appear in observations or available tool metadata; otherwise produce a final_answer step explaining that template discovery is unavailable.\n");
            prompt.append("- If prior observations already contain structured sql_metadata_search columns/types/comments, preserve that evidence and do not re-add metadata search unless more data is explicitly missing and the tool is available.\n");
        }
        prompt.append("- Read templates[].requiredParameters, templates[].parameterContract, and templates[].invocationExample as authoritative. If requiredParameters contains tableName, fill sql_query_execute.input.parameters.tableName from the user request, sql_metadata_search result, or table-location result.\n");
        prompt.append("- Never retry a template execution with empty parameters when the template declares required parameters; add/bind the missing parameters first.\n");
        prompt.append("- sql_query_execute must include executionContext from asset discovery, for example {\"assetName\":\"<asset-name>\",\"env\":\"<env>\"}; do not put routing fields only under parameters.\n");
        prompt.append("- Do not inline JSONPath placeholders such as $.assets[0].asset.name inside executionContext; either use plan.bindings or depend on the asset_query step and let runtime inject concrete values.\n");
        if (metadataSearchAvailable) {
            prompt.append("- When schema/database is unknown, add sql_metadata_search before any available template_query/sql_query_execute. Pass tableName and includeColumns=true for table analysis, then bind from results[].sqlExecutionBinding.\n");
        } else {
            prompt.append("- sql_metadata_search is not available; do not guess schema/database from free text or from assetName. Use only already observed table-location evidence.\n");
        }
        prompt.append("- Never bind asset_query assets[].asset.name into parameters.schemaName. Asset name is routing context; schemaName/databaseName must come from sql_metadata_search or a table-location template.\n");
        prompt.append("- Do not invent SQL or template IDs; use an already observed template_query result, or add a new template_query step only when that tool is listed in Available tools.\n\n");
        prompt.append("HTTP/API/SSH template repair rules:\n");
        prompt.append("- For http_request_execute and linux_command_execute, bind a returned templates[].templateId into input.template and pass only parameters declared by templates[].parameterSchema under input.parameters.\n");
        prompt.append("- For API service tools returned by api_template_query, call the returned toolName/templateId exactly and pass only arguments declared by templates[].parameterSchema/parameterContract.\n");
        prompt.append("- Remove raw HTTP fields url/uri/method/headers/body/endpointId and raw SSH fields command/rawCommand/shell/host/hostname/ip/hostId from execution inputs. Replan through template discovery if needed.\n");
        prompt.append("- Never retry HTTP/API/SSH template execution with empty parameters when requiredParameters is non-empty; add/bind the missing parameters first.\n\n");
        prompt.append("InterpretationPlan JSON Schema:\n").append(InterpretationPlanJsonSchema.SCHEMA).append("\n\n");
        prompt.append("Available tools:\n").append(request.availableTools() == null ? List.of() : request.availableTools()).append("\n");
        prompt.append("Failed step:\n").append(toJson(failedStep(request))).append("\n");
        prompt.append("Failure reason:\n").append(request.failureReason()).append("\n");
        prompt.append("Observations so far:\n").append(request.observations() == null ? List.of() : request.observations()).append("\n");
        prompt.append("Original plan:\n").append(toJson(request.originalPlan()));
        return prompt.toString();
    }

    private boolean hasAvailableSemanticTool(List<String> availableTools, String semanticToolName) {
        if (availableTools == null || availableTools.isEmpty() || semanticToolName == null || semanticToolName.isBlank()) {
            return false;
        }
        for (String toolName : availableTools) {
            String semantic = toolSemanticKey(toolName);
            if (semanticToolName.equals(semantic)
                || ("template_discovery".equals(semanticToolName)
                    && ("template_query".equals(semantic) || semantic.endsWith("_template_query")))) {
                return true;
            }
        }
        return false;
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

    private Map<String, Object> failedStep(RewriteRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (request.failedStep() == null) {
            return values;
        }
        values.put("id", request.failedStep().id());
        values.put("action_type", request.failedStep().actionType());
        values.put("tool_name", request.failedStep().toolName());
        values.put("input", request.failedStep().input());
        values.put("depends_on", request.failedStep().dependsOn());
        return values;
    }

    private String toJson(Object value) {
        return ModelProtocolJson.compact(value);
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private InterpretationPlan repairContinuationPlan(InterpretationPlan originalPlan, InterpretationPlan rewrittenPlan) {
        if (originalPlan == null || rewrittenPlan == null
            || originalPlan.steps().isEmpty() || rewrittenPlan.steps().isEmpty()) {
            return rewrittenPlan;
        }
        Map<Integer, InterpretationPlan.Step> originalStepsById = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : originalPlan.steps()) {
            if (step != null && step.id() != null) {
                originalStepsById.put(step.id(), step);
            }
        }
        if (originalStepsById.isEmpty()) {
            return rewrittenPlan;
        }

        Set<Integer> rewrittenStepIds = stepIds(rewrittenPlan.steps());
        Set<Integer> missingStepIds = referencedStepIds(rewrittenPlan);
        missingStepIds.removeAll(rewrittenStepIds);
        missingStepIds.retainAll(originalStepsById.keySet());
        if (missingStepIds.isEmpty()) {
            return rewrittenPlan;
        }

        collectTransitiveOriginalDependencies(missingStepIds, rewrittenStepIds, originalStepsById);
        List<InterpretationPlan.Step> mergedSteps = new ArrayList<>();
        for (InterpretationPlan.Step step : originalPlan.steps()) {
            if (step != null && step.id() != null && missingStepIds.contains(step.id())) {
                mergedSteps.add(step);
            }
        }
        mergedSteps.addAll(rewrittenPlan.steps());
        if (mergedSteps.size() == rewrittenPlan.steps().size()) {
            return rewrittenPlan;
        }

        InterpretationPlan.Plan rewrittenBody = rewrittenPlan.plan();
        InterpretationPlan.Plan repairedBody = new InterpretationPlan.Plan(
            mergedSteps,
            rewrittenBody == null || rewrittenBody.edgeContracts() == null ? List.of() : rewrittenBody.edgeContracts(),
            rewrittenBody == null || rewrittenBody.bindings() == null ? List.of() : rewrittenBody.bindings(),
            rewrittenBody == null ? null : rewrittenBody.stability()
        );
        return new InterpretationPlan(
            rewrittenPlan.version(),
            rewrittenPlan.intent(),
            rewrittenPlan.context(),
            repairedBody,
            repairExecutionPolicy(originalPlan.executionPolicy(), rewrittenPlan.executionPolicy(), mergedSteps),
            rewrittenPlan.review()
        );
    }

    private InterpretationPlan repairExecutionPolicyStepLimit(InterpretationPlan plan) {
        if (plan == null || plan.executionPolicy() == null) {
            return plan;
        }
        int stepCount = plan.steps() == null ? 0 : plan.steps().size();
        Integer maxSteps = plan.executionPolicy().maxSteps();
        if (maxSteps == null || maxSteps >= stepCount) {
            return plan;
        }
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        InterpretationPlan.ExecutionPolicy repairedPolicy = new InterpretationPlan.ExecutionPolicy(
            stepCount,
            policy.allowParallel(),
            policy.allowTool(),
            policy.denyTool(),
            policy.timeoutMs(),
            policy.maxRewriteTimes(),
            policy.fallbackMode(),
            policy.toolPriority(),
            policy.costBudget(),
            policy.latencyBudgetMs(),
            policy.accuracyVsSpeed()
        );
        return new InterpretationPlan(
            plan.version(),
            plan.intent(),
            plan.context(),
            plan.plan(),
            repairedPolicy,
            plan.review()
        );
    }

    private void collectTransitiveOriginalDependencies(Set<Integer> missingStepIds,
                                                       Set<Integer> rewrittenStepIds,
                                                       Map<Integer, InterpretationPlan.Step> originalStepsById) {
        ArrayDeque<Integer> pending = new ArrayDeque<>(missingStepIds);
        while (!pending.isEmpty()) {
            Integer stepId = pending.removeFirst();
            InterpretationPlan.Step step = originalStepsById.get(stepId);
            if (step == null || step.dependsOn() == null) {
                continue;
            }
            for (Integer dependency : step.dependsOn()) {
                if (dependency == null || rewrittenStepIds.contains(dependency) || !originalStepsById.containsKey(dependency)) {
                    continue;
                }
                if (missingStepIds.add(dependency)) {
                    pending.addLast(dependency);
                }
            }
        }
    }

    private Set<Integer> stepIds(List<InterpretationPlan.Step> steps) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (steps == null) {
            return ids;
        }
        for (InterpretationPlan.Step step : steps) {
            if (step != null && step.id() != null) {
                ids.add(step.id());
            }
        }
        return ids;
    }

    private Set<Integer> referencedStepIds(InterpretationPlan plan) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (plan == null || plan.plan() == null) {
            return ids;
        }
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step != null && step.dependsOn() != null) {
                ids.addAll(step.dependsOn());
            }
        }
        if (plan.plan().edgeContracts() != null) {
            for (InterpretationPlan.EdgeContract contract : plan.plan().edgeContracts()) {
                if (contract == null) {
                    continue;
                }
                addIfPresent(ids, contract.from());
                addIfPresent(ids, contract.to());
            }
        }
        if (plan.plan().bindings() != null) {
            for (InterpretationPlan.Binding binding : plan.plan().bindings()) {
                if (binding == null) {
                    continue;
                }
                addIfPresent(ids, binding.from());
                addIfPresent(ids, binding.to());
            }
        }
        InterpretationPlan.Stability stability = plan.plan().stability();
        if (stability != null && stability.stableNodes() != null) {
            ids.addAll(stability.stableNodes());
        }
        ids.remove(null);
        return ids;
    }

    private void addIfPresent(Set<Integer> ids, Integer value) {
        if (value != null) {
            ids.add(value);
        }
    }

    private InterpretationPlan.ExecutionPolicy repairExecutionPolicy(InterpretationPlan.ExecutionPolicy originalPolicy,
                                                                     InterpretationPlan.ExecutionPolicy rewrittenPolicy,
                                                                     List<InterpretationPlan.Step> mergedSteps) {
        if (rewrittenPolicy == null) {
            return originalPolicy;
        }
        Integer maxSteps = rewrittenPolicy.maxSteps();
        int stepCount = mergedSteps == null ? 0 : mergedSteps.size();
        if (maxSteps != null && maxSteps < stepCount) {
            maxSteps = stepCount;
        }
        return new InterpretationPlan.ExecutionPolicy(
            maxSteps,
            rewrittenPolicy.allowParallel(),
            mergeTools(originalPolicy == null ? null : originalPolicy.allowTool(), rewrittenPolicy.allowTool()),
            rewrittenPolicy.denyTool(),
            rewrittenPolicy.timeoutMs(),
            rewrittenPolicy.maxRewriteTimes(),
            rewrittenPolicy.fallbackMode(),
            rewrittenPolicy.toolPriority(),
            rewrittenPolicy.costBudget(),
            rewrittenPolicy.latencyBudgetMs(),
            rewrittenPolicy.accuracyVsSpeed()
        );
    }

    private List<String> mergeTools(List<String> left, List<String> right) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        if (left != null) {
            tools.addAll(left);
        }
        if (right != null) {
            tools.addAll(right);
        }
        return new ArrayList<>(tools);
    }

    private String validationSummary(InterpretationPlanValidator.ValidationResult validation) {
        if (validation == null || validation.errors() == null || validation.errors().isEmpty()) {
            return "unknown validation error";
        }
        return validation.errors().stream()
            .map(InterpretationPlanValidator.ValidationIssue::message)
            .filter(message -> message != null && !message.isBlank())
            .limit(5)
            .toList()
            .toString();
    }

    public record RewriteRequest(
        InterpretationPlan originalPlan,
        InterpretationPlan.Step failedStep,
        String failureReason,
        List<String> observations,
        List<String> availableTools,
        ToolRegistry toolRegistry
    ) {
    }

    public record RewriteResult(
        boolean valid,
        boolean executable,
        InterpretationPlan rewrittenPlan,
        InterpretationPlanValidator.ValidationResult validation,
        String rawResponse,
        String errorMessage
    ) {
        private static RewriteResult failed(String errorMessage, String rawResponse, InterpretationPlan rewrittenPlan) {
            return new RewriteResult(false, false, rewrittenPlan, null, rawResponse, errorMessage);
        }
    }
}
