package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                return new RewriteResult(false, false, rewrittenPlan, validation, raw,
                    "Rewritten plan failed validation");
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
        prompt.append("InterpretationPlan JSON Schema:\n").append(InterpretationPlanJsonSchema.SCHEMA).append("\n\n");
        prompt.append("Available tools:\n").append(request.availableTools() == null ? List.of() : request.availableTools()).append("\n");
        prompt.append("Failed step:\n").append(toJson(failedStep(request))).append("\n");
        prompt.append("Failure reason:\n").append(request.failureReason()).append("\n");
        prompt.append("Observations so far:\n").append(request.observations() == null ? List.of() : request.observations()).append("\n");
        prompt.append("Original plan:\n").append(toJson(request.originalPlan()));
        return prompt.toString();
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
