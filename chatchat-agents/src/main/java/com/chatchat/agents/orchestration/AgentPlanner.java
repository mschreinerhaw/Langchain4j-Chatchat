package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanJsonSchema;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds planner prompts and parses planner decisions.
 */
@Slf4j
class AgentPlanner {

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String FINAL = "final";
    private static final String TOOL = "tool";
    private static final int DEFAULT_PLAN_REPAIR_ATTEMPTS = 3;
    private static final int MAX_PLAN_REPAIR_ATTEMPTS = 3;

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final InterpretationPlanValidator interpretationPlanValidator = new InterpretationPlanValidator();

    AgentPlanner(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    AgentDecision decideNextAction(ChatModel activeChatModel,
                                   String query,
                                   String systemPrompt,
                                   List<String> availableTools,
                                   List<String> observations,
                                   List<String> boundDocumentIds,
                                   List<String> boundDocumentTags,
                                   List<String> mandatoryTools,
                                   boolean requireToolBeforeFinal,
                                   boolean requireDocumentWebVerification,
                                   String documentSearchTool,
                                   String verificationWebSearchTool,
                                   Map<String, Object> runtimeAttributes) {
        String prompt = buildPlannerPrompt(
            query,
            systemPrompt,
            availableTools,
            observations,
            boundDocumentIds,
            boundDocumentTags,
            mandatoryTools,
            requireToolBeforeFinal,
            requireDocumentWebVerification,
            documentSearchTool,
            verificationWebSearchTool,
            runtimeAttributes
        );
        PlannerValidationContext validationContext = new PlannerValidationContext(
            normalizeList(mandatoryTools),
            requireToolBeforeFinal,
            requireDocumentWebVerification,
            documentSearchTool,
            verificationWebSearchTool,
            normalizeList(availableTools)
        );
        String runId = stringValue(runtimeAttributes == null ? null : runtimeAttributes.get("__agentRunId"));
        int maxAttempts = plannerRepairAttempts(runtimeAttributes);
        String currentPrompt = prompt;
        AgentDecision lastDecision = null;
        String lastRaw = null;
        String logRunId = runId == null ? "" : runId;
        List<PlanCandidate> candidates = new ArrayList<>();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            log.info("agentModelRequest phase=planner runId={} attempt={}/{} modelClass={} promptChars={} toolCount={} observationCount={}",
                logRunId,
                attempt,
                maxAttempts,
                activeChatModel == null ? null : activeChatModel.getClass().getName(),
                currentPrompt.length(),
                availableTools == null ? 0 : availableTools.size(),
                observations == null ? 0 : observations.size());
            String raw = activeChatModel.chat(currentPrompt);
            lastRaw = raw;
            log.info("agentModelResponse phase=planner runId={} attempt={}/{} durationMs={} responseChars={}",
                logRunId,
                attempt,
                maxAttempts,
                System.currentTimeMillis() - startedAt,
                raw == null ? 0 : raw.length());
            logPlannerRawOutput(logRunId, attempt, maxAttempts, raw);
            AgentDecision decision = parseDecision(raw, validationContext);
            if (decision == null) {
                lastDecision = invalidPlannerDecision(raw, "non_json_response", "Planner did not return valid JSON.");
                logPlannerDecision(logRunId, attempt, maxAttempts, lastDecision);
            } else {
                logPlannerDecision(logRunId, attempt, maxAttempts, decision);
            }
            if (decision != null && !plannerPlanInvalid(decision)) {
                return decision;
            }
            if (decision != null) {
                lastDecision = decision;
            }
            candidates.add(planCandidate(attempt, raw, lastDecision, validationContext));
            if (attempt < maxAttempts && shouldRepairPlan(lastDecision, validationContext)) {
                currentPrompt = buildPlannerRepairPrompt(prompt, raw, lastDecision, attempt + 1, maxAttempts);
                continue;
            }
            break;
        }
        PlanRewriteContext rewriteContext = planRewriteContext(candidates);
        if (rewriteContext.rewriteCount() >= MAX_PLAN_REPAIR_ATTEMPTS) {
            AgentDecision attributionDecision = attributeAndSelectBestPlan(
                activeChatModel,
                prompt,
                rewriteContext,
                validationContext,
                logRunId
            );
            if (attributionDecision != null) {
                return attributionDecision;
            }
        }
        return lastDecision == null
            ? invalidPlannerDecision(lastRaw, "non_json_response", "Planner did not return valid JSON.")
            : lastDecision;
    }

    private String buildPlannerPrompt(String query,
                                      String systemPrompt,
                                      List<String> availableTools,
                                      List<String> observations,
                                      List<String> boundDocumentIds,
                                      List<String> boundDocumentTags,
                                      List<String> mandatoryTools,
                                      boolean requireToolBeforeFinal,
                                      boolean requireDocumentWebVerification,
                                      String documentSearchTool,
                                      String verificationWebSearchTool,
                                      Map<String, Object> runtimeAttributes) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are an agent planner.\n");
        prompt.append("Goal: produce a safe, executable InterpretationPlan for the MCP runtime.\n");
        prompt.append("Planning contract:\n");
        prompt.append("- Output exactly one JSON object. Do not output markdown, code fences, comments, or natural language.\n");
        prompt.append("- The JSON object MUST conform to the InterpretationPlan schema below.\n");
        prompt.append("- The user query MUST first be converted into this executable InterpretationPlan before any tool execution.\n");
        prompt.append("- Do not output legacy action/tool JSON such as {\"action\":\"tool\"} or {\"action\":\"final\"}.\n");
        prompt.append("- The plan is declarative. Do not claim that a tool has already run unless it appears in Observations so far.\n");
        prompt.append("- Use integer step ids starting at 1. Keep depends_on as explicit arrays of prior step ids.\n");
        prompt.append("- Include exactly one final_answer step. Put the user-facing answer in final_answer.input.answer only when observations are sufficient.\n");
        prompt.append("- For mcp_tool steps, tool_name MUST be one of the available tools and input MUST be the exact tool payload.\n");
        prompt.append("- If the user specifies an official source or website, preserve that source constraint in the relevant tool input.\n");
        prompt.append("- Use execution_policy.allow_tool only for tools intentionally approved by policy context; use deny_tool for tools that must never run.\n");
        prompt.append("- Use execution_policy.max_rewrite_times to bound automatic replanning; default to 1 for tool-backed plans.\n");
        prompt.append("- Use execution_policy.fallback_mode as safe_answer or partial_result when tools may fail.\n");
        prompt.append("- Do not set execution_policy.timeout_ms for MCP tools that may search, crawl, query data, or otherwise run for a long time; omit timeout_ms unless runtime policy explicitly provides one.\n");
        prompt.append("- Use execution_policy.tool_priority, cost_budget, latency_budget_ms, and accuracy_vs_speed when policy context constrains cost, latency, or quality.\n");
        prompt.append("- Use plan.stability to lock critical nodes/tools/edges that optimizer and rewriter must preserve.\n");
        prompt.append("- Add plan.edge_contracts when a later step needs a typed field from an earlier tool output.\n");
        prompt.append("- If information is missing, add missing_info and plan the smallest safe retrieval/tool step instead of inventing facts.\n\n");
        if (requireToolBeforeFinal) {
            prompt.append("Mandatory tool policy:\n");
            prompt.append("- This agent is bound to required runtime tools. Your response MUST be an InterpretationPlan that includes the required tool steps.\n");
            prompt.append("- Do not make the final_answer step independent until all required tools have been called and observed.\n");
            prompt.append("- Required tools are ordered by workflow or runtime policy: ").append(mandatoryTools).append("\n");
            prompt.append("- If no required tool has been observed yet, include the first required tool as the first executable mcp_tool step.\n");
            prompt.append("- Do not place a tool from a later workflow stage before earlier required stages have succeeded.\n");
            prompt.append("- Tools listed in the same workflow parallel stage may be represented as independent steps with the same dependencies.\n");
            prompt.append("- If the user request is analytical, portfolio-related, market-related, data-driven, or requires validation, include the mandatory tools before final_answer.\n\n");
        }
        prompt.append("Respond with strict JSON only.\n");
        prompt.append("You MUST output ONLY a valid InterpretationPlan JSON following schema. No natural language.\n");
        prompt.append("If you cannot produce a valid plan, output a final_answer step whose input.answer explains the missing requirement.\n");
        prompt.append("InterpretationPlan JSON Schema:\n");
        prompt.append(InterpretationPlanJsonSchema.SCHEMA).append("\n\n");
        prompt.append("Final answer policy:\n");
        prompt.append("- Set review.self_check.tool_sufficiency=true only when observations already satisfy the user request without another tool call.\n");
        prompt.append("- Runtime policy may still reject final answers when required tool or verification constraints are incomplete.\n\n");
        prompt.append("Available tools:\n").append(describeTools(availableTools, runtimeAttributes)).append("\n");
        String discoverySearchTool = preferredWebSearchTool(availableTools);
        String crawlerTool = preferredCrawlerTool(availableTools);
        if (discoverySearchTool != null && crawlerTool != null) {
            prompt.append("Web evidence workflow:\n");
            prompt.append("1. Use ").append(discoverySearchTool)
                .append(" and other web discovery tools only to discover candidate pages, page links, search routes, and short snippets. Do not treat discovery snippets as final evidence.\n");
            prompt.append("2. After a web discovery tool returns candidates, runtime will ask the model to choose relevant URLs.\n");
            prompt.append("3. Then call ").append(crawlerTool)
                .append(" to fetch cleaned full page content from the selected URL before analysis.\n");
            prompt.append("4. The final_answer step MUST depend on the crawler/content step, not only on web discovery.\n");
            prompt.append("5. If an official website or exchange site is required, keep that source constraint in the web discovery query/input.\n\n");
            prompt.append("Discovery tools include web_search, web_page_analyze, site_intelligence_resolver, finance_site_search, and generic_web_site_search when available.\n\n");
            prompt.append("Crawler input contract:\n");
            prompt.append("- Never use ").append(crawlerTool).append(" as a search tool. It cannot accept a free-text query.\n");
            prompt.append("- ").append(crawlerTool).append(" may only be called with an HTTP/HTTPS url selected from prior web discovery results, for example {\"url\":\"https://example.com/page\"}.\n");
            prompt.append("- If no URL has been observed yet, call a web discovery tool first and do not call ").append(crawlerTool).append(".\n\n");
            prompt.append("Binding contract:\n");
            prompt.append("- Use plan.bindings for data flow from one step output to another step input. edge_contracts only validate data shape; they do not populate inputs.\n");
            prompt.append("- When ").append(crawlerTool).append(" depends on ").append(discoverySearchTool)
                .append(", bind the selected search result URL into crawler input url, e.g. {\"from\":1,\"output_path\":\"$.results[0].url\",\"to\":2,\"input_field\":\"url\",\"type\":\"jsonpath\"}.\n");
            prompt.append("- Do not use placeholder inputs such as {\"url\":\"\"} or template strings such as ${step1.results[0].url}; use plan.bindings instead.\n\n");
            prompt.append("Web search query fidelity:\n");
            prompt.append("- Current date for relative-time interpretation is ")
                .append(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                .append(" (Asia/Shanghai).\n");
            prompt.append("- For web_search.query, preserve the user's original search phrase as much as possible. Do not append inferred years, stale years, or extra date tokens.\n");
            prompt.append("- If the user says today, latest, current, recent, \u4eca\u5929, \u6700\u65b0, \u8fd1\u671f, or \u5f53\u524d, keep that temporal wording instead of converting it to another year unless the user explicitly requested an absolute date.\n\n");
        }
        if (!boundDocumentIds.isEmpty() || !boundDocumentTags.isEmpty()) {
            prompt.append("Knowledge document search scope:\n");
            if (!boundDocumentIds.isEmpty()) {
                prompt.append("- document_ids: ").append(boundDocumentIds).append("\n");
            }
            if (!boundDocumentTags.isEmpty()) {
                prompt.append("- tags: ").append(boundDocumentTags).append("\n");
            }
            prompt.append("Document workflow:\n");
            prompt.append("1. If the user asks about research material, reports, files, or document-backed facts, call ")
                .append(firstNonBlank(documentSearchTool, DOCUMENT_SEARCH_TOOL))
                .append(" first.\n");
            prompt.append("2. Keep ").append(firstNonBlank(documentSearchTool, DOCUMENT_SEARCH_TOOL))
                .append(" within the configured document_ids/tags scope.\n");
            prompt.append("3. Use retrieved evidence as the basis of the final answer; if evidence is insufficient, say what is missing.\n");
            prompt.append("4. Do not invent facts beyond retrieved documents and tool observations.\n\n");
        }
        if (requireDocumentWebVerification) {
            prompt.append("Document-web verification workflow:\n");
            prompt.append("1. Call ").append(documentSearchTool).append(" first to retrieve internal knowledge evidence.\n");
            prompt.append("2. Then call ").append(verificationWebSearchTool).append(" to validate and supplement with public/web evidence.\n");
            prompt.append("3. Do not return a final answer until both ").append(documentSearchTool).append(" and ")
                .append(verificationWebSearchTool)
                .append(" have been observed.\n");
            prompt.append("4. In the final answer, separate internal document evidence from web verification evidence.\n");
            prompt.append("5. If the two sources conflict, explicitly state the conflict and prefer internal documents for internal/business facts unless web evidence is newer and the answer calls for current public facts.\n\n");
        }
        if (!observations.isEmpty()) {
            prompt.append("Observations so far:\n");
            observations.forEach(ob -> prompt.append("- ").append(ob).append("\n"));
            prompt.append("Citation requirement:\n");
            prompt.append("- If observations include web citation labels such as [缃戦〉1], cite web-derived statements with the matching label immediately after the sentence.\n");
            prompt.append("- Do not cite web facts without a matching citation label from the observations.\n");
            prompt.append("\n");
        }
        prompt.append("User query:\n").append(query);
        return prompt.toString();
    }

    private String describeTools(List<String> availableTools, Map<String, Object> runtimeAttributes) {
        if (availableTools == null || availableTools.isEmpty()) {
            return "- (none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String toolName : availableTools) {
            ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
            String configuredDescription = configuredToolDescription(toolName, runtimeAttributes);
            if (metadata != null) {
                sb.append("- ")
                    .append(toolName)
                    .append(": ")
                    .append(firstNonBlank(configuredDescription, metadata.getDescription()))
                    .append("\n");
            } else {
                ToolRegistry.Tool simpleTool = toolRegistry.getTool(toolName);
                String description = simpleTool == null ? "No description available" : simpleTool.getDescription();
                sb.append("- ").append(toolName).append(": ")
                    .append(firstNonBlank(configuredDescription, description))
                    .append("\n");
            }
        }
        return sb.toString();
    }

    private String configuredToolDescription(String toolName, Map<String, Object> runtimeAttributes) {
        if (toolName == null || toolName.isBlank() || runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return null;
        }
        Object configs = runtimeAttributes.get("mcpToolConfigs");
        if (!(configs instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            Map<String, Object> config = asMap(item);
            if (config.isEmpty()) {
                continue;
            }
            String configuredToolName = firstNonBlank(
                stringValue(firstObject(config, "toolName", "tool")),
                stringValue(firstObject(config, "name"))
            );
            if (!sameToolName(configuredToolName, toolName)) {
                continue;
            }
            String description = stringValue(config.get("description"));
            return description == null || description.isBlank() ? null : description.trim();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private AgentDecision parseDecision(String raw, PlannerValidationContext validationContext) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJson(raw);
        try {
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            AgentDecision interpretationPlanDecision = parseInterpretationPlanDecision(payload, validationContext);
            if (interpretationPlanDecision != null) {
                return interpretationPlanDecision;
            }
            if (requiresStrictInterpretationPlan(validationContext)) {
                return invalidPlannerDecision(
                    raw,
                    "legacy_action_not_allowed",
                    "MCP workflow requires an InterpretationPlan; legacy action JSON is not allowed."
                );
            }
            String action = stringValue(payload.get("action"));
            if (action == null) {
                return null;
            }
            action = action.toLowerCase(Locale.ROOT);
            Map<String, Object> executionPlan = asMap(payload.get("executionPlan"));
            if (FINAL.equals(action)) {
                Boolean sufficient = booleanObject(firstObject(payload, "sufficient", "isSufficient"));
                if (sufficient == null) {
                    sufficient = booleanObject(firstObject(executionPlan, "sufficient", "isSufficient"));
                }
                return new AgentDecision(
                    FINAL,
                    null,
                    Map.of(),
                    stringValue(payload.get("answer")),
                    stringValue(payload.get("reason")),
                    executionPlan,
                    sufficient
                );
            }
            if (!TOOL.equals(action)) {
                return null;
            }
            Object argsObj = payload.get("arguments");
            Map<String, Object> arguments = argsObj instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            return new AgentDecision(
                TOOL,
                stringValue(payload.get("toolName")),
                arguments,
                null,
                stringValue(payload.get("reason")),
                executionPlan,
                null
            );
        } catch (Exception ex) {
            log.debug("Failed to parse planner decision: {}", raw, ex);
            return null;
        }
    }

    private AgentDecision parseInterpretationPlanDecision(Map<String, Object> payload,
                                                         PlannerValidationContext validationContext) {
        if (payload == null || !payload.containsKey("plan") || !payload.containsKey("intent")) {
            return null;
        }
        InterpretationPlan interpretationPlan = objectMapper.convertValue(payload, InterpretationPlan.class);
        InterpretationPlanValidator.ValidationResult validation =
            interpretationPlanValidator.validate(
                interpretationPlan,
                toolRegistry,
                new LinkedHashSet<>(validationContext == null ? List.of() : normalizeList(validationContext.availableTools()))
            );
        List<String> runtimeIssues = validateRuntimePlanRules(interpretationPlan, validationContext);
        Map<String, Object> validationMetadata = validationMetadata(validation, runtimeIssues);
        if (!validation.valid() || !runtimeIssues.isEmpty()) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                "Planner produced an invalid InterpretationPlan.",
                "invalid_interpretation_plan",
                validationMetadata,
                false,
                interpretationPlan
            );
        }
        InterpretationPlan.Step nextStep = nextExecutableStep(interpretationPlan);
        if (nextStep == null) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                answerFromFinalStep(interpretationPlan, null),
                "interpretation_plan_without_actionable_step",
                validationMetadata,
                null,
                interpretationPlan
            );
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(validationMetadata);
        executionPlan.put("plan_step_id", nextStep.id());
        executionPlan.put("action_type", nextStep.actionType());
        executionPlan.put("intent", interpretationPlan.intent() == null ? null : interpretationPlan.intent().goal());
        executionPlan.put("risk_level", interpretationPlan.intent() == null ? null : interpretationPlan.intent().riskLevel());
        executionPlan.put("tool", nextStep.toolName());

        if (nextStep.mcpToolAction()) {
            return new AgentDecision(
                TOOL,
                nextStep.toolName(),
                nextStep.input() == null ? Map.of() : nextStep.input(),
                null,
                interpretationPlan.intent() == null ? null : interpretationPlan.intent().goal(),
                executionPlan,
                false,
                interpretationPlan
            );
        }
        if (nextStep.finalAnswerAction()) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                answerFromFinalStep(interpretationPlan, nextStep),
                "interpretation_plan_final_answer",
                executionPlan,
                interpretationPlan.review() != null
                    && interpretationPlan.review().selfCheck() != null
                    && Boolean.TRUE.equals(interpretationPlan.review().selfCheck().toolSufficiency()),
                interpretationPlan
            );
        }
        return new AgentDecision(
            FINAL,
            null,
            Map.of(),
            answerFromFinalStep(interpretationPlan, nextStep),
            "interpretation_plan_reasoning_step",
            executionPlan,
            null,
            interpretationPlan
        );
    }

    private InterpretationPlan.Step nextExecutableStep(InterpretationPlan interpretationPlan) {
        if (interpretationPlan == null || interpretationPlan.steps().isEmpty()) {
            return null;
        }
        return interpretationPlan.steps().stream()
            .filter(step -> step != null && (step.mcpToolAction() || step.finalAnswerAction()))
            .findFirst()
            .orElse(interpretationPlan.steps().get(0));
    }

    private String answerFromFinalStep(InterpretationPlan interpretationPlan, InterpretationPlan.Step finalStep) {
        Map<String, Object> input = finalStep == null ? Map.of() : finalStep.input();
        String answer = firstNonBlank(
            stringValue(firstObject(input, "answer", "response", "text", "result")),
            null
        );
        if (answer != null) {
            return answer;
        }
        return interpretationPlan == null || interpretationPlan.intent() == null
            ? ""
            : firstNonBlank(interpretationPlan.intent().goal(), "");
    }

    private Map<String, Object> validationMetadata(InterpretationPlanValidator.ValidationResult validation,
                                                   List<String> runtimeIssues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plannerProtocol", "interpretation_plan");
        boolean runtimeValid = runtimeIssues == null || runtimeIssues.isEmpty();
        metadata.put("interpretationPlanValid", validation.valid() && runtimeValid);
        metadata.put("interpretationPlanSchemaValid", validation.valid());
        metadata.put("interpretationPlanRuntimeRulesValid", runtimeValid);
        metadata.put("interpretationPlanExecutable", validation.executable() && runtimeValid);
        metadata.put("interpretationPlanApprovalRequired", validation.approvalRequired());
        metadata.put("interpretationPlanIssues", validation.issues().stream()
            .map(issue -> Map.of(
                "severity", issue.severity(),
                "path", issue.path(),
                "message", issue.message()
            ))
            .toList());
        metadata.put("interpretationPlanRuntimeIssues", runtimeIssues == null ? List.of() : runtimeIssues);
        metadata.put("orderedStepIds", validation.orderedSteps().stream()
            .map(InterpretationPlan.Step::id)
            .toList());
        return metadata;
    }

    private List<String> validateRuntimePlanRules(InterpretationPlan plan, PlannerValidationContext context) {
        if (context == null || plan == null) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        List<String> mandatoryTools = normalizeList(context.mandatoryTools());
        if (context.requireToolBeforeFinal() && mandatoryTools.isEmpty()) {
            issues.add("Runtime policy requires a tool before final answer, but no mandatory tool was provided.");
        }
        Map<Integer, InterpretationPlan.Step> stepsById = new LinkedHashMap<>();
        Map<String, List<Integer>> toolStepIds = new LinkedHashMap<>();
        InterpretationPlan.Step finalStep = null;
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null || step.id() == null) {
                continue;
            }
            stepsById.put(step.id(), step);
            if (step.finalAnswerAction()) {
                finalStep = step;
            }
            if (step.mcpToolAction() && step.toolName() != null && !step.toolName().isBlank()) {
                toolStepIds.computeIfAbsent(step.toolName(), ignored -> new ArrayList<>()).add(step.id());
            }
        }
        Integer previousMandatoryStepId = null;
        for (String mandatoryTool : mandatoryTools) {
            Integer mandatoryStepId = firstToolStepId(toolStepIds, mandatoryTool);
            if (mandatoryStepId == null) {
                issues.add("Mandatory tool is missing from InterpretationPlan: " + mandatoryTool);
                continue;
            }
            if (previousMandatoryStepId != null && mandatoryStepId <= previousMandatoryStepId) {
                issues.add("Mandatory tools must appear in configured order: " + mandatoryTool);
            }
            if (finalStep == null || !dependsOnStep(finalStep.id(), mandatoryStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on mandatory tool before answering: " + mandatoryTool);
            }
            previousMandatoryStepId = mandatoryStepId;
        }
        if (context.requireDocumentWebVerification()) {
            Integer documentStepId = firstToolStepId(toolStepIds, context.documentSearchTool());
            Integer webStepId = firstToolStepId(toolStepIds, context.verificationWebSearchTool());
            boolean documentRequiredInPlan = containsTool(mandatoryTools, context.documentSearchTool());
            boolean webRequiredInPlan = containsTool(mandatoryTools, context.verificationWebSearchTool());
            if (documentRequiredInPlan && documentStepId == null) {
                issues.add("Document-web verification requires document tool in plan: " + context.documentSearchTool());
            }
            if (webRequiredInPlan && webStepId == null) {
                issues.add("Document-web verification requires web verification tool in plan: " + context.verificationWebSearchTool());
            }
            if (documentStepId != null && webStepId != null && webStepId <= documentStepId) {
                issues.add("Document-web verification must plan document search before web verification.");
            }
            if (documentRequiredInPlan && finalStep != null && documentStepId != null
                && !dependsOnStep(finalStep.id(), documentStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on document verification evidence.");
            }
            if (webRequiredInPlan && finalStep != null && webStepId != null
                && !dependsOnStep(finalStep.id(), webStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on web verification evidence.");
            }
        }
        validateWebSearchCrawlerSplit(plan, context, stepsById, toolStepIds, finalStep, issues);
        return issues;
    }

    private void validateWebSearchCrawlerSplit(InterpretationPlan plan,
                                               PlannerValidationContext context,
                                               Map<Integer, InterpretationPlan.Step> stepsById,
                                               Map<String, List<Integer>> toolStepIds,
                                               InterpretationPlan.Step finalStep,
                                               List<String> issues) {
        if (plan == null || context == null || issues == null) {
            return;
        }
        String crawlerTool = preferredCrawlerTool(context.availableTools());
        if (crawlerTool == null) {
            return;
        }
        List<Integer> webDiscoverySteps = new ArrayList<>();
        List<Integer> crawlerSteps = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : toolStepIds.entrySet()) {
            if (isWebDiscoveryTool(entry.getKey())) {
                webDiscoverySteps.addAll(entry.getValue());
            } else if (sameToolName(entry.getKey(), crawlerTool) || isCrawlerTool(entry.getKey())) {
                crawlerSteps.addAll(entry.getValue());
            }
        }
        if (webDiscoverySteps.isEmpty()) {
            return;
        }
        if (crawlerSteps.isEmpty()) {
            issues.add("web discovery must be followed by a crawler/content tool before final_answer: " + crawlerTool);
            return;
        }
        for (Integer webStepId : webDiscoverySteps) {
            boolean hasCrawlerAfterDiscovery = crawlerSteps.stream()
                .anyMatch(crawlerStepId -> crawlerStepId > webStepId
                    && dependsOnStep(crawlerStepId, webStepId, stepsById, new LinkedHashSet<>()));
            if (!hasCrawlerAfterDiscovery) {
                issues.add("crawler/content step must depend on each web discovery step before analysis.");
            }
        }
        if (finalStep != null) {
            boolean finalDependsOnCrawler = crawlerSteps.stream()
                .anyMatch(crawlerStepId -> dependsOnStep(finalStep.id(), crawlerStepId, stepsById, new LinkedHashSet<>()));
            if (!finalDependsOnCrawler) {
                issues.add("final_answer must depend on crawler/content evidence, not only on web discovery snippets.");
            }
        }
    }

    private String preferredWebSearchTool(List<String> availableTools) {
        List<String> tools = normalizeList(availableTools);
        return tools.stream()
            .filter(this::isWebSearchTool)
            .findFirst()
            .orElse(null);
    }

    private String preferredCrawlerTool(List<String> availableTools) {
        List<String> tools = normalizeList(availableTools);
        String crawlUrl = tools.stream()
            .filter(tool -> "crawl_url".equals(toolSemanticKey(tool)) || toolSemanticKey(tool).endsWith("_crawl_url"))
            .findFirst()
            .orElse(null);
        if (crawlUrl != null) {
            return crawlUrl;
        }
        return tools.stream()
            .filter(this::isCrawlerTool)
            .findFirst()
            .orElse(null);
    }

    private boolean isWebSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search");
    }

    private boolean isWebDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return isWebSearchTool(toolName)
            || semantic.equals("web_page_analyze")
            || semantic.contains("web_page_analyze")
            || semantic.equals("site_intelligence_resolver")
            || semantic.contains("site_intelligence")
            || semantic.equals("finance_site_search")
            || semantic.contains("finance_site_search")
            || semantic.equals("generic_web_site_search")
            || semantic.contains("generic_web_site_search")
            || semantic.equals("web_site_search")
            || (semantic.contains("site_search") && !semantic.contains("search_and_extract"));
    }

    private boolean isCrawlerTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return !isWebDiscoveryTool(toolName)
            && (semantic.equals("crawl_url")
            || semantic.contains("crawl")
            || semantic.contains("crawler")
            || semantic.contains("fetch_page")
            || semantic.contains("page_content")
            || semantic.contains("download")
            || semantic.contains("extract"));
    }

    private boolean containsTool(List<String> tools, String toolName) {
        if (toolName == null || toolName.isBlank() || tools == null || tools.isEmpty()) {
            return false;
        }
        return tools.stream().anyMatch(tool -> sameToolName(tool, toolName));
    }

    private Integer firstToolStepId(Map<String, List<Integer>> toolStepIds, String toolName) {
        if (toolName == null || toolName.isBlank() || toolStepIds == null || toolStepIds.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<Integer>> entry : toolStepIds.entrySet()) {
            if (sameToolName(entry.getKey(), toolName) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private boolean dependsOnStep(Integer fromStepId,
                                  Integer requiredDependencyId,
                                  Map<Integer, InterpretationPlan.Step> stepsById,
                                  Set<Integer> visited) {
        if (fromStepId == null || requiredDependencyId == null) {
            return false;
        }
        if (fromStepId.equals(requiredDependencyId)) {
            return true;
        }
        if (visited == null) {
            visited = new LinkedHashSet<>();
        }
        if (!visited.add(fromStepId)) {
            return false;
        }
        InterpretationPlan.Step from = stepsById.get(fromStepId);
        if (from == null || from.dependsOn() == null || from.dependsOn().isEmpty()) {
            return false;
        }
        if (from.dependsOn().contains(requiredDependencyId)) {
            return true;
        }
        for (Integer dependency : from.dependsOn()) {
            if (dependsOnStep(dependency, requiredDependencyId, stepsById, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresStrictInterpretationPlan(PlannerValidationContext context) {
        return context != null
            && (context.requireToolBeforeFinal()
            || context.requireDocumentWebVerification()
            || !normalizeList(context.mandatoryTools()).isEmpty());
    }

    private boolean plannerPlanInvalid(AgentDecision decision) {
        return decision != null
            && "invalid_interpretation_plan".equals(decision.reason())
            && decision.executionPlan() != null
            && "interpretation_plan".equals(decision.executionPlan().get("plannerProtocol"));
    }

    private boolean shouldRepairPlan(AgentDecision decision, PlannerValidationContext context) {
        return decision != null
            && (plannerPlanInvalid(decision) || (requiresStrictInterpretationPlan(context)
            && "legacy_action_not_allowed".equals(decision.reason())));
    }

    private void logPlannerRawOutput(String runId, int attempt, int maxAttempts, String raw) {
        log.info("agentPlannerRawOutput phase=planner runId={} attempt={}/{} chars={} content=\n{}",
            runId,
            attempt,
            maxAttempts,
            raw == null ? 0 : raw.length(),
            raw == null ? "" : raw);
    }

    private void logPlannerDecision(String runId, int attempt, int maxAttempts, AgentDecision decision) {
        if (decision == null) {
            log.warn("agentPlannerDecision phase=planner_parse runId={} attempt={}/{} status=null_decision",
                runId, attempt, maxAttempts);
            return;
        }
        Map<String, Object> executionPlan = decision.executionPlan() == null ? Map.of() : decision.executionPlan();
        Object protocol = executionPlan.get("plannerProtocol");
        Object valid = executionPlan.get("interpretationPlanValid");
        Object executable = executionPlan.get("interpretationPlanExecutable");
        Object runtimeIssues = executionPlan.get("interpretationPlanRuntimeIssues");
        log.info(
            "agentPlannerDecision phase=planner_parse runId={} attempt={}/{} action={} reason={} protocol={} planPresent={} valid={} executable={} sufficient={} toolName={} runtimeIssues={} answerPreview={}",
            runId,
            attempt,
            maxAttempts,
            decision.action(),
            decision.reason(),
            protocol,
            decision.interpretationPlan() != null,
            valid,
            executable,
            decision.sufficient(),
            decision.toolName(),
            runtimeIssues,
            abbreviate(decision.answer(), 500)
        );
        if (decision.interpretationPlan() != null) {
            log.info("agentPlannerInterpretationPlan phase=planner_parse runId={} attempt={}/{} planJson=\n{}",
                runId,
                attempt,
                maxAttempts,
                prettyJson(decision.interpretationPlan()));
            log.info("agentPlannerInterpretationPlanValidation phase=planner_parse runId={} attempt={}/{} validationMetadata=\n{}",
                runId,
                attempt,
                maxAttempts,
                prettyJson(executionPlan));
        } else {
            log.warn("agentPlannerNoInterpretationPlan phase=planner_parse runId={} attempt={}/{} action={} reason={} rawAnswerPreview={}",
                runId,
                attempt,
                maxAttempts,
                decision.action(),
                decision.reason(),
                abbreviate(decision.answer(), 1000));
        }
    }

    private String prettyJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private AgentDecision invalidPlannerDecision(String raw, String reason, String issue) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plannerProtocol", "interpretation_plan");
        metadata.put("interpretationPlanValid", false);
        metadata.put("interpretationPlanSchemaValid", false);
        metadata.put("interpretationPlanRuntimeRulesValid", false);
        metadata.put("interpretationPlanExecutable", false);
        metadata.put("interpretationPlanRuntimeIssues", issue == null || issue.isBlank() ? List.of() : List.of(issue));
        return new AgentDecision(
            FINAL,
            null,
            Map.of(),
            raw,
            reason,
            metadata,
            false
        );
    }

    private String buildPlannerRepairPrompt(String originalPrompt,
                                            String previousOutput,
                                            AgentDecision invalidDecision,
                                            int nextAttempt,
                                            int maxAttempts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(originalPrompt).append("\n\n");
        prompt.append("Previous planner output was rejected by runtime validation.\n");
        prompt.append("Repair attempt: ").append(nextAttempt).append('/').append(maxAttempts).append("\n");
        prompt.append("Validation issues:\n");
        Object runtimeIssues = invalidDecision == null || invalidDecision.executionPlan() == null
            ? null
            : invalidDecision.executionPlan().get("interpretationPlanRuntimeIssues");
        if (runtimeIssues instanceof List<?> issues && !issues.isEmpty()) {
            for (Object issue : issues) {
                prompt.append("- ").append(issue).append("\n");
            }
        } else {
            prompt.append("- ").append(invalidDecision == null ? "Invalid planner output" : invalidDecision.reason()).append("\n");
        }
        prompt.append("Rejected output:\n").append(previousOutput == null ? "" : previousOutput).append("\n\n");
        prompt.append("Regenerate the entire response as strict InterpretationPlan JSON only. ");
        prompt.append("Do not omit any mandatory MCP tool and do not return legacy action JSON.");
        return prompt.toString();
    }

    private PlanRewriteContext planRewriteContext(List<PlanCandidate> candidates) {
        List<PlanCandidate> values = candidates == null ? List.of() : List.copyOf(candidates);
        PlanCandidate last = values.isEmpty() ? null : values.get(values.size() - 1);
        String lastFailureReason = last == null || last.decision() == null ? "unknown" : last.decision().reason();
        String failurePattern = dominantFailurePattern(values);
        return new PlanRewriteContext(values.size(), values, lastFailureReason, failurePattern);
    }

    private PlanCandidate planCandidate(int attempt,
                                        String raw,
                                        AgentDecision decision,
                                        PlannerValidationContext validationContext) {
        String label = String.valueOf((char) ('A' + Math.max(0, attempt - 1)));
        String failurePattern = failurePattern(decision);
        String fingerprint = planFingerprint(decision);
        int score = deterministicPlanScore(decision, validationContext);
        return new PlanCandidate(attempt, label, raw, decision, failurePattern, fingerprint, score);
    }

    private String dominantFailurePattern(List<PlanCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "UNKNOWN";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlanCandidate candidate : candidates) {
            counts.merge(candidate.failurePattern(), 1, Integer::sum);
        }
        String bestPattern = "UNKNOWN";
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestPattern = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestPattern;
    }

    private String failurePattern(AgentDecision decision) {
        if (decision == null) {
            return "NON_JSON";
        }
        if ("legacy_action_not_allowed".equals(decision.reason())) {
            return "LEGACY_ACTION";
        }
        List<String> issues = plannerIssues(decision).stream()
            .map(issue -> issue.toLowerCase(Locale.ROOT))
            .toList();
        if (issues.stream().anyMatch(issue -> issue.contains("missing") || issue.contains("not found")
            || issue.contains("available tool") || issue.contains("unavailable") || issue.contains("unknown tool"))) {
            return "TOOL_MISSING";
        }
        if (issues.stream().anyMatch(issue -> issue.contains("depend") || issue.contains("step")
            || issue.contains("cycle") || issue.contains("final_answer"))) {
            return "DAG_INVALID";
        }
        Object schemaValid = decision.executionPlan() == null ? null : decision.executionPlan().get("interpretationPlanSchemaValid");
        if (Boolean.FALSE.equals(schemaValid)) {
            return "SCHEMA_INVALID";
        }
        if (!issues.isEmpty()) {
            return "RUNTIME_POLICY";
        }
        return "UNKNOWN";
    }

    private String planFingerprint(AgentDecision decision) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null || plan.steps().isEmpty()) {
            return "no-plan";
        }
        StringBuilder canonical = new StringBuilder();
        for (InterpretationPlan.Step step : plan.steps()) {
            canonical.append(step.id()).append('|')
                .append(step.actionType()).append('|')
                .append(step.toolName()).append('|')
                .append(step.dependsOn()).append(';');
        }
        return Integer.toHexString(canonical.toString().hashCode());
    }

    private int deterministicPlanScore(AgentDecision decision, PlannerValidationContext validationContext) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null) {
            return 0;
        }
        int score = 0;
        score += toolAvailabilityScore(plan, validationContext);
        score += dagValidityScore(decision);
        score += executionCostScore(plan);
        score += runtimePolicyFitScore(plan, decision, validationContext);
        return Math.max(0, Math.min(100, score));
    }

    private int toolAvailabilityScore(InterpretationPlan plan, PlannerValidationContext validationContext) {
        List<String> tools = plan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .toList();
        if (tools.isEmpty()) {
            return 0;
        }
        long available = tools.stream()
            .filter(tool -> toolAvailable(tool, validationContext))
            .count();
        return (int) Math.round(30.0 * available / tools.size());
    }

    private boolean toolAvailable(String toolName, PlannerValidationContext validationContext) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (validationContext != null && normalizeList(validationContext.availableTools()).stream().anyMatch(tool -> sameToolName(tool, toolName))) {
            return true;
        }
        return toolRegistry != null && toolRegistry.hasTool(toolName);
    }

    private int dagValidityScore(AgentDecision decision) {
        Map<String, Object> metadata = decision == null || decision.executionPlan() == null ? Map.of() : decision.executionPlan();
        int score = 0;
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanSchemaValid"))) {
            score += 10;
        }
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanRuntimeRulesValid"))) {
            score += 10;
        }
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanExecutable"))) {
            score += 5;
        }
        return score;
    }

    private int executionCostScore(InterpretationPlan plan) {
        long toolSteps = plan.steps().stream().filter(InterpretationPlan.Step::mcpToolAction).count();
        if (toolSteps <= 0) {
            return 0;
        }
        return (int) Math.max(5, 20 - Math.max(0, toolSteps - 1) * 3);
    }

    private int runtimePolicyFitScore(InterpretationPlan plan,
                                      AgentDecision decision,
                                      PlannerValidationContext validationContext) {
        int score = 25;
        List<String> issues = plannerIssues(decision);
        score -= Math.min(15, issues.size() * 5);
        List<String> mandatoryTools = validationContext == null ? List.of() : normalizeList(validationContext.mandatoryTools());
        for (String mandatoryTool : mandatoryTools) {
            boolean present = plan.steps().stream()
                .anyMatch(step -> step.mcpToolAction() && sameToolName(step.toolName(), mandatoryTool));
            if (!present) {
                score -= 10;
            }
        }
        return Math.max(0, score);
    }

    private AgentDecision attributeAndSelectBestPlan(ChatModel activeChatModel,
                                                     String originalPrompt,
                                                     PlanRewriteContext rewriteContext,
                                                     PlannerValidationContext validationContext,
                                                     String runId) {
        if (activeChatModel == null || rewriteContext == null || rewriteContext.candidates().isEmpty()) {
            return null;
        }
        String prompt = buildAttributionSelectionPrompt(originalPrompt, rewriteContext);
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=planner_attribution runId={} candidateCount={} promptChars={}",
            runId == null ? "" : runId,
            rewriteContext.candidates().size(),
            prompt.length());
        String raw = activeChatModel.chat(prompt);
        log.info("agentModelResponse phase=planner_attribution runId={} durationMs={} responseChars={}",
            runId == null ? "" : runId,
            System.currentTimeMillis() - startedAt,
            raw == null ? 0 : raw.length());
        log.info("agentModelRawOutput phase=planner_attribution runId={} raw=\n{}", runId == null ? "" : runId, raw == null ? "" : raw);
        AttributionSelection selection = parseAttributionSelection(raw, validationContext);
        if (selection.decision() == null) {
            return null;
        }
        AgentDecision attributed = withAttributionMetadata(selection, raw, rewriteContext);
        logPlannerDecision(runId == null ? "" : runId, rewriteContext.rewriteCount() + 1, rewriteContext.rewriteCount() + 1, attributed);
        return plannerPlanInvalid(attributed) ? null : attributed;
    }

    private String buildAttributionSelectionPrompt(String originalPrompt, PlanRewriteContext rewriteContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(originalPrompt).append("\n\n");
        prompt.append("The planner has regenerated a plan exactly 3 times, and no candidate passed runtime validation.\n");
        prompt.append("You are now in bounded attribution selection mode, not free regeneration mode.\n");
        prompt.append("Root failure pattern: ").append(rewriteContext.failurePattern()).append("\n");
        prompt.append("Last failure reason: ").append(rewriteContext.lastFailureReason()).append("\n\n");
        prompt.append("Task:\n");
        prompt.append("1. Identify failure patterns across candidates A/B/C.\n");
        prompt.append("2. Score each candidate from 0 to 100.\n");
        prompt.append("3. Select exactly one candidate label: A, B, or C.\n");
        prompt.append("4. Return an executable InterpretationPlan in the plan field, based on the selected candidate only.\n");
        prompt.append("5. Apply only minimal guard-required repairs; do not invent a fourth unrelated plan.\n\n");
        prompt.append("Deterministic scoring bias to respect:\n");
        prompt.append("- tool availability: 30%\n");
        prompt.append("- DAG validity: 25%\n");
        prompt.append("- execution cost: 20%\n");
        prompt.append("- runtime policy fit / success-history similarity: 25%\n\n");
        prompt.append("Output strict JSON only with this shape:\n");
        prompt.append("{\"analysis\":\"...\",\"scores\":{\"A\":0,\"B\":0,\"C\":0},\"selected\":\"A|B|C\",\"reason\":\"...\",\"plan\":{InterpretationPlan JSON}}\n\n");
        prompt.append("Failed planner candidates:\n");
        for (PlanCandidate candidate : rewriteContext.candidates()) {
            prompt.append("\nCandidate ").append(candidate.label()).append(" (attempt ").append(candidate.attempt()).append("):\n");
            prompt.append("Deterministic pre-score: ").append(candidate.deterministicScore()).append("/100\n");
            prompt.append("Failure pattern: ").append(candidate.failurePattern()).append("\n");
            prompt.append("Fingerprint: ").append(candidate.fingerprint()).append("\n");
            prompt.append("Validation issues:\n");
            List<String> issues = plannerIssues(candidate.decision());
            if (issues.isEmpty()) {
                prompt.append("- Unknown planner/runtime validation failure\n");
            } else {
                issues.forEach(issue -> prompt.append("- ").append(issue).append("\n"));
            }
            prompt.append("Raw output:\n").append(candidate.raw() == null ? "" : candidate.raw()).append("\n");
        }
        return prompt.toString();
    }

    @SuppressWarnings("unchecked")
    private AttributionSelection parseAttributionSelection(String raw, PlannerValidationContext validationContext) {
        if (raw == null || raw.isBlank()) {
            return new AttributionSelection(null, null, null, Map.of(), null);
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(extractJson(raw), Map.class);
            String selected = stringValue(payload.get("selected"));
            String analysis = stringValue(payload.get("analysis"));
            String reason = stringValue(payload.get("reason"));
            Map<String, Object> scores = asMap(payload.get("scores"));
            Map<String, Object> plan = asMap(payload.get("plan"));
            if (!plan.isEmpty()) {
                AgentDecision decision = parseDecision(objectMapper.writeValueAsString(plan), validationContext);
                return new AttributionSelection(decision, selected, analysis, scores, reason);
            }
        } catch (Exception ex) {
            log.debug("Failed to parse attribution selection wrapper: {}", raw, ex);
        }
        AgentDecision decision = parseDecision(raw, validationContext);
        return new AttributionSelection(decision, null, null, Map.of(), null);
    }

    private List<String> plannerIssues(AgentDecision decision) {
        if (decision == null || decision.executionPlan() == null || decision.executionPlan().isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        Object runtimeIssues = decision.executionPlan().get("interpretationPlanRuntimeIssues");
        if (runtimeIssues instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(issues::add);
        }
        Object validationIssues = decision.executionPlan().get("interpretationPlanIssues");
        if (validationIssues instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(issues::add);
        }
        return issues.stream()
            .filter(issue -> issue != null && !issue.isBlank())
            .distinct()
            .toList();
    }

    private AgentDecision withAttributionMetadata(AttributionSelection selection,
                                                  String raw,
                                                  PlanRewriteContext rewriteContext) {
        AgentDecision decision = selection == null ? null : selection.decision();
        if (decision == null) {
            return null;
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(decision.executionPlan() == null ? Map.of() : decision.executionPlan());
        executionPlan.put("plannerAttributionSelection", true);
        executionPlan.put("plannerAttributionCandidateCount", rewriteContext == null ? 0 : rewriteContext.candidates().size());
        executionPlan.put("plannerAttributionSelected", selection.selected());
        executionPlan.put("plannerAttributionReason", selection.reason());
        executionPlan.put("plannerAttributionAnalysis", abbreviate(selection.analysis(), 2000));
        executionPlan.put("plannerAttributionScores", selection.scores() == null ? Map.of() : selection.scores());
        executionPlan.put("plannerAttributionFailurePattern", rewriteContext == null ? "UNKNOWN" : rewriteContext.failurePattern());
        executionPlan.put("plannerAttributionCandidateFingerprints", rewriteContext == null ? List.of() : rewriteContext.candidates().stream()
            .map(candidate -> Map.of(
                "label", candidate.label(),
                "fingerprint", candidate.fingerprint(),
                "failurePattern", candidate.failurePattern(),
                "deterministicScore", candidate.deterministicScore()
            ))
            .toList());
        executionPlan.put("plannerAttributionRaw", abbreviate(raw, 2000));
        return new AgentDecision(
            decision.action(),
            decision.toolName(),
            decision.arguments(),
            decision.answer(),
            decision.reason(),
            executionPlan,
            decision.sufficient(),
            decision.interpretationPlan()
        );
    }

    private int plannerRepairAttempts(Map<String, Object> runtimeAttributes) {
        Object configured = runtimeAttributes == null ? null : runtimeAttributes.get("plannerMaxRepairAttempts");
        if (configured instanceof Number number) {
            return Math.max(1, Math.min(MAX_PLAN_REPAIR_ATTEMPTS, number.intValue()));
        }
        if (configured != null) {
            try {
                return Math.max(1, Math.min(MAX_PLAN_REPAIR_ATTEMPTS, Integer.parseInt(String.valueOf(configured))));
            } catch (NumberFormatException ignored) {
                return DEFAULT_PLAN_REPAIR_ATTEMPTS;
            }
        }
        return DEFAULT_PLAN_REPAIR_ATTEMPTS;
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

    private String extractJson(String raw) {
        String text = raw.trim();
        int blockStart = text.indexOf("```");
        if (blockStart >= 0) {
            int firstBrace = text.indexOf('{', blockStart);
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return text.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
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

    private String normalizeKnownToolAlias(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return toolName;
        }
        String semantic = toolSemanticKey(toolName);
        if (semantic.contains("document") && semantic.contains("search")) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search")) {
            return "web_search";
        }
        if (semantic.contains("search_and_extract")) {
            return "search_and_extract";
        }
        return toolName.trim();
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}

record PlannerValidationContext(
    List<String> mandatoryTools,
    boolean requireToolBeforeFinal,
    boolean requireDocumentWebVerification,
    String documentSearchTool,
    String verificationWebSearchTool,
    List<String> availableTools
) {
}

record AgentDecision(
    String action,
    String toolName,
    Map<String, Object> arguments,
    String answer,
    String reason,
    Map<String, Object> executionPlan,
    Boolean sufficient,
    InterpretationPlan interpretationPlan
) {
    AgentDecision(String action,
                  String toolName,
                  Map<String, Object> arguments,
                  String answer,
                  String reason,
                  Map<String, Object> executionPlan,
                  Boolean sufficient) {
        this(action, toolName, arguments, answer, reason, executionPlan, sufficient, null);
    }
}

record PlanRewriteContext(
    int rewriteCount,
    List<PlanCandidate> candidates,
    String lastFailureReason,
    String failurePattern
) {
}

record PlanCandidate(
    int attempt,
    String label,
    String raw,
    AgentDecision decision,
    String failurePattern,
    String fingerprint,
    int deterministicScore
) {
}

record AttributionSelection(
    AgentDecision decision,
    String selected,
    String analysis,
    Map<String, Object> scores,
    String reason
) {
}
