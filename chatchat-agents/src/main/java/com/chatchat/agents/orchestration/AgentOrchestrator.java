package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Agent orchestrator with tool planning and execution loop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private static final int MAX_STEPS = 3;
    private static final int WEB_SEARCH_REFERENCE_LIMIT = 10;
    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String FINAL = "final";
    private static final String TOOL = "tool";
    private static final String REVIEW_ACCEPTED = "accepted";
    private static final String REVIEW_REVISED = "revised";

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolRuntimeService toolRuntimeService;
    private final ObjectMapper objectMapper;
    private final ModelsConfig modelsConfig;
    private final Map<String, ChatModel> chatModelsByName = new ConcurrentHashMap<>();

    /**
     * Executes the agent.
     *
     * @param query the query value
     * @param tenantId the tenant id value
     * @param availableTools the available tools value
     * @param systemPrompt the system prompt value
     * @param modelName the model name value
     * @param boundDocumentIds the bound document ids value
     * @param boundDocumentTags the bound document tags value
     * @param skillId the skill id value
     * @param requestId the request id value
     * @param conversationId the conversation id value
     * @param userId the user id value
     * @param webSearchResultLimit the web search result limit value
     * @param requiredToolNames the required tool names value
     * @param requireBoundToolCall the require bound tool call value
     * @return the operation result
     */
    public AgentExecutionResult executeAgent(String query,
                                             String tenantId,
                                             List<String> availableTools,
                                             String systemPrompt,
                                             String modelName,
                                             List<String> boundDocumentIds,
                                             List<String> boundDocumentTags,
                                             String skillId,
                                             String requestId,
                                             String conversationId,
                                             String userId,
                                             int webSearchResultLimit,
                                             List<String> requiredToolNames,
                                             boolean requireBoundToolCall) {
        return executeAgent(query, tenantId, availableTools, systemPrompt, modelName, boundDocumentIds,
            boundDocumentTags, skillId, requestId, conversationId, userId, webSearchResultLimit,
            requiredToolNames, requireBoundToolCall, Map.of());
    }

    /**
     * Executes the agent.
     *
     * @param query the query value
     * @param tenantId the tenant id value
     * @param availableTools the available tools value
     * @param systemPrompt the system prompt value
     * @param modelName the model name value
     * @param boundDocumentIds the bound document ids value
     * @param boundDocumentTags the bound document tags value
     * @param skillId the skill id value
     * @param requestId the request id value
     * @param conversationId the conversation id value
     * @param userId the user id value
     * @param webSearchResultLimit the web search result limit value
     * @param requiredToolNames the required tool names value
     * @param requireBoundToolCall the require bound tool call value
     * @param runtimeAttributes the runtime attributes value
     * @return the operation result
     */
    public AgentExecutionResult executeAgent(String query,
                                             String tenantId,
                                             List<String> availableTools,
                                             String systemPrompt,
                                             String modelName,
                                             List<String> boundDocumentIds,
                                             List<String> boundDocumentTags,
                                             String skillId,
                                             String requestId,
                                             String conversationId,
                                             String userId,
                                             int webSearchResultLimit,
                                             List<String> requiredToolNames,
                                             boolean requireBoundToolCall,
                                             Map<String, Object> runtimeAttributes) {
        List<String> tools = availableTools == null ? List.of() : availableTools;
        Map<String, Object> requestRuntimeAttributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
        BooleanSupplier cancellationCheck = cancellationCheck(requestRuntimeAttributes);
        List<String> documentIds = normalizeList(boundDocumentIds);
        List<String> documentTags = normalizeList(boundDocumentTags);
        String documentSearchTool = resolveDocumentSearchTool(tools);
        String verificationWebSearchTool = resolveVerificationWebSearchTool(tools);
        boolean requireDocumentWebVerification = shouldRequireDocumentWebVerification(
            tools,
            documentSearchTool,
            verificationWebSearchTool,
            documentIds,
            documentTags
        );
        List<String> workflowMandatoryTools = resolveWorkflowMandatoryTools(tools, requestRuntimeAttributes);
        List<String> mandatoryTools = workflowMandatoryTools.isEmpty()
            ? resolveMandatoryToolCandidates(tools, requiredToolNames, requireBoundToolCall)
            : workflowMandatoryTools;
        if (requireDocumentWebVerification) {
            mandatoryTools = withDocumentWebVerificationMandatoryTools(mandatoryTools, documentSearchTool, verificationWebSearchTool);
        }
        boolean requireToolBeforeFinal = !mandatoryTools.isEmpty();
        ChatModel activeChatModel = resolveChatModel(modelName);
        List<InteractionToolTrace> traces = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Map<String, Object>> plannerSteps = new ArrayList<>();
        metadata.put("skillId", skillId == null ? "general" : skillId);
        metadata.put("modelName", normalizeModelName(modelName));
        metadata.put("boundDocumentIds", documentIds);
        metadata.put("boundDocumentTags", documentTags);
        metadata.put("availableTools", tools);
        metadata.put("webSearchResultLimit", webSearchResultLimit);
        metadata.put("mandatoryToolCall", requireToolBeforeFinal);
        metadata.put("mandatoryTools", mandatoryTools);
        metadata.put("workflowMandatoryTools", workflowMandatoryTools);
        metadata.put("requiredToolNames", normalizeList(requiredToolNames));
        metadata.put("documentWebVerificationRequired", requireDocumentWebVerification);
        metadata.put("documentSearchTool", documentSearchTool);
        metadata.put("verificationWebSearchTool", verificationWebSearchTool);
        metadata.put("plannerSteps", plannerSteps);

        log.info("[{}] Agent orchestration started. tools={}", requestId, tools.size());

        Set<String> completedWorkflowTools = new LinkedHashSet<>();
        ToolCallExecution pendingConfirmedExecution = executePendingConfirmedTool(
            query,
            conversationId,
            requestId,
            userId,
            tenantId,
            tools,
            attributesWithCompletedTools(runtimeAttributes, completedWorkflowTools)
        );
        if (pendingConfirmedExecution != null) {
            traces.add(pendingConfirmedExecution.trace());
            observations.add("Confirmed pending " + pendingConfirmedExecution.observation());
            rememberCompletedWorkflowTool(completedWorkflowTools, pendingConfirmedExecution);
            metadata.put("resumedPendingToolExecution", true);
            metadata.put("resumedPendingTool", pendingConfirmedExecution.trace() == null
                ? null
                : pendingConfirmedExecution.trace().getToolName());
            if (isConfirmationRequired(pendingConfirmedExecution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return new AgentExecutionResult("", traces, metadata);
            }
        }

        for (int step = 1; step <= MAX_STEPS; step++) {
            checkCancelled(cancellationCheck);
            long plannedAt = System.currentTimeMillis();
            AgentDecision decision = decideNextAction(
                activeChatModel,
                query,
                systemPrompt,
                tools,
                observations,
                documentIds,
                documentTags,
                mandatoryTools,
                requireToolBeforeFinal && traces.isEmpty(),
                requireDocumentWebVerification,
                documentSearchTool,
                verificationWebSearchTool,
                requestRuntimeAttributes
            );
            checkCancelled(cancellationCheck);
            String plannedToolName = normalizeToolName(decision.toolName(), tools);
            metadata.put("steps", step);
            Map<String, Object> plannerStep = new LinkedHashMap<>();
            plannerStep.put("step", step);
            plannerStep.put("action", decision.action());
            plannerStep.put("toolName", stringValue(decision.toolName()));
            plannerStep.put("resolvedToolName", plannedToolName);
            plannerStep.put("reason", stringValue(decision.reason()));
            plannerStep.put("executionPlan", decision.executionPlan());
            plannerStep.put("answerPreview", preview(decision.answer()));
            plannerStep.put("plannedAt", plannedAt);
            plannerStep.put("observationCount", observations.size());
            plannerSteps.add(plannerStep);

            if (FINAL.equals(decision.action())) {
                checkCancelled(cancellationCheck);
                List<String> missingMandatoryTools = missingMandatoryTools(mandatoryTools, traces);
                if (requireToolBeforeFinal && !missingMandatoryTools.isEmpty()) {
                    observations.add("Planner final answer rejected: this MCP-bound agent must observe all mandatory workflow tools before final answer. Missing: "
                        + missingMandatoryTools);
                    metadata.put("rejectedFinalBeforeTool", true);
                    metadata.put("missingMandatoryTools", missingMandatoryTools);
                    continue;
                }
                if (requireDocumentWebVerification && missingDocumentWebVerification(traces, documentSearchTool, verificationWebSearchTool)) {
                    observations.add("Planner final answer rejected: document-web verification requires both document_search and "
                        + verificationWebSearchTool + " observations before final answer.");
                    metadata.put("rejectedFinalBeforeVerification", true);
                    continue;
                }
                String answer = safeAnswer(activeChatModel, decision.answer(), query, observations, systemPrompt);
                checkCancelled(cancellationCheck);
                AnswerReview review = reviewAndReviseAnswer(activeChatModel, query, systemPrompt, observations, answer);
                checkCancelled(cancellationCheck);
                recordAnswerReview(metadata, review);
                metadata.put("stopReason", "final_answer");
                return new AgentExecutionResult(review.answer(), traces, metadata);
            }

            if (!TOOL.equals(decision.action())) {
                observations.add("Planner returned unsupported action, fallback to final answer.");
                break;
            }

            if (plannedToolName == null || plannedToolName.isBlank()) {
                observations.add("Planner requested tool action without toolName.");
                break;
            }
            if (!tools.contains(plannedToolName)) {
                observations.add("Planner requested unavailable tool: " + decision.toolName());
                continue;
            }
            if (requireToolBeforeFinal && traces.isEmpty() && !containsToolName(mandatoryTools, plannedToolName)) {
                observations.add("Planner requested non-mandatory tool before MCP-bound evidence: " + plannedToolName);
                continue;
            }
            if (requireDocumentWebVerification
                && !hasToolTrace(traces, documentSearchTool)
                && !sameToolName(documentSearchTool, plannedToolName)) {
                observations.add("Planner requested " + plannedToolName
                    + " before " + documentSearchTool + "; document-web verification must start with " + documentSearchTool + ".");
                continue;
            }

            Map<String, Object> arguments = applyToolDefaults(
                plannedToolName,
                decision.arguments(),
                documentIds,
                documentTags,
                query,
                webSearchResultLimit
            );
            checkCancelled(cancellationCheck);
            ToolCallExecution execution = executeToolCall(
                plannedToolName,
                arguments,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                decision.executionPlan(),
                attributesWithCompletedTools(requestRuntimeAttributes, completedWorkflowTools)
            );
            traces.add(execution.trace());
            observations.add(execution.observation());
            if (isConfirmationRequired(execution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return new AgentExecutionResult("", traces, metadata);
            }
            rememberCompletedWorkflowTool(completedWorkflowTools, execution);
            checkCancelled(cancellationCheck);
        }

        if (requireToolBeforeFinal && traces.isEmpty()) {
            checkCancelled(cancellationCheck);
            String fallbackTool = mandatoryTools.get(0);
            Map<String, Object> fallbackArguments = applyDocumentSearchDefaults(
                fallbackTool,
                defaultToolArguments(fallbackTool, query, webSearchResultLimit),
                documentIds,
                documentTags
            );
            ToolCallExecution execution = executeToolCall(
                fallbackTool,
                fallbackArguments,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                Map.of(),
                attributesWithCompletedTools(requestRuntimeAttributes, completedWorkflowTools)
            );
            traces.add(execution.trace());
            observations.add("Mandatory fallback " + execution.observation());
            metadata.put("mandatoryToolFallback", fallbackTool);
            if (isConfirmationRequired(execution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return new AgentExecutionResult("", traces, metadata);
            }
            rememberCompletedWorkflowTool(completedWorkflowTools, execution);
        }
        if (requireDocumentWebVerification) {
            checkCancelled(cancellationCheck);
            runMissingDocumentWebVerification(
                traces,
                observations,
                query,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                documentSearchTool,
                documentIds,
                documentTags,
                webSearchResultLimit,
                verificationWebSearchTool,
                metadata,
                requestRuntimeAttributes
            );
            if (Boolean.TRUE.equals(metadata.get("confirmationRequired"))) {
                return new AgentExecutionResult("", traces, metadata);
            }
        }

        checkCancelled(cancellationCheck);
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations);
        checkCancelled(cancellationCheck);
        AnswerReview review = reviewAndReviseAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer);
        checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", "max_steps_or_fallback");
        return new AgentExecutionResult(review.answer(), traces, metadata);
    }

    /**
     * Performs the decide next action operation.
     *
     * @param activeChatModel the active chat model value
     * @param query the query value
     * @param systemPrompt the system prompt value
     * @param availableTools the available tools value
     * @param observations the observations value
     * @param boundDocumentIds the bound document ids value
     * @param boundDocumentTags the bound document tags value
     * @param mandatoryTools the mandatory tools value
     * @param requireToolBeforeFinal the require tool before final value
     * @param requireDocumentWebVerification the require document web verification value
     * @param documentSearchTool the document search tool value
     * @param verificationWebSearchTool the verification web search tool value
     * @param runtimeAttributes the runtime attributes value
     * @return the operation result
     */
    private AgentDecision decideNextAction(ChatModel activeChatModel,
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
        String raw = activeChatModel.chat(prompt);
        AgentDecision decision = parseDecision(raw);
        if (decision == null) {
            return new AgentDecision(FINAL, null, Map.of(), raw, "non_json_response", Map.of());
        }
        return decision;
    }

    /**
     * Builds the planner prompt.
     *
     * @param query the query value
     * @param systemPrompt the system prompt value
     * @param availableTools the available tools value
     * @param observations the observations value
     * @param boundDocumentIds the bound document ids value
     * @param boundDocumentTags the bound document tags value
     * @param mandatoryTools the mandatory tools value
     * @param requireToolBeforeFinal the require tool before final value
     * @param requireDocumentWebVerification the require document web verification value
     * @param documentSearchTool the document search tool value
     * @param verificationWebSearchTool the verification web search tool value
     * @param runtimeAttributes the runtime attributes value
     * @return the built planner prompt
     */
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
        prompt.append("Goal: solve the user query with zero or more tools.\n");
        if (requireToolBeforeFinal) {
            prompt.append("Mandatory tool policy:\n");
            prompt.append("- This agent is bound to required runtime tools. Your next response MUST be a tool action.\n");
            prompt.append("- Do not return a final answer until all required tools have been called and observed.\n");
            prompt.append("- Required tools are ordered by workflow or runtime policy: ").append(mandatoryTools).append("\n");
            prompt.append("- If no required tool has been observed yet, call the first required tool in that ordered list.\n");
            prompt.append("- Do not call a tool from a later workflow stage before earlier required stages have succeeded.\n");
            prompt.append("- Tools listed in the same workflow parallel stage may be called in any order.\n");
            prompt.append("- If the user request is analytical, portfolio-related, market-related, data-driven, or requires validation, use a mandatory tool first.\n\n");
        }
        prompt.append("Respond with strict JSON only.\n");
        prompt.append("Schema:\n");
        prompt.append("{\"action\":\"final\",\"answer\":\"...\"}\n");
        prompt.append("or\n");
        prompt.append("{\"action\":\"tool\",\"toolName\":\"...\",\"arguments\":{...},\"reason\":\"...\",");
        prompt.append("\"executionPlan\":{\"workflow\":\"optional_workflow_name\",\"intent\":\"...\",\"tool\":\"...\",\"operation_type\":\"read|write|send|delete\",");
        prompt.append("\"risk_level\":\"low|medium|high|forbidden\",\"parameters\":{...},\"reason\":\"...\"}}\n\n");
        prompt.append("Available tools:\n").append(describeTools(availableTools, runtimeAttributes)).append("\n");
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
            prompt.append("- If observations include web citation labels such as [网页1], cite web-derived statements with the matching label immediately after the sentence.\n");
            prompt.append("- Do not cite web facts without a matching citation label from the observations.\n");
            prompt.append("\n");
        }
        prompt.append("User query:\n").append(query);
        return prompt.toString();
    }

    /**
     * Performs the describe tools operation.
     *
     * @param availableTools the available tools value
     * @param runtimeAttributes the runtime attributes value
     * @return the operation result
     */
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

    /**
     * Performs the configured tool description operation.
     *
     * @param toolName the tool name value
     * @param runtimeAttributes the runtime attributes value
     * @return the operation result
     */
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

    /**
     * Parses the decision.
     *
     * @param raw the raw value
     * @return the parsed decision
     */
    @SuppressWarnings("unchecked")
    private AgentDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJson(raw);
        try {
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            String action = stringValue(payload.get("action"));
            if (action == null) {
                return null;
            }
            action = action.toLowerCase(Locale.ROOT);
            if (FINAL.equals(action)) {
                return new AgentDecision(FINAL, null, Map.of(), stringValue(payload.get("answer")), stringValue(payload.get("reason")), Map.of());
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
                asMap(payload.get("executionPlan"))
            );
        } catch (Exception ex) {
            log.debug("Failed to parse planner decision: {}", raw, ex);
            return null;
        }
    }

    /**
     * Performs the extract json operation.
     *
     * @param raw the raw value
     * @return the operation result
     */
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

    /**
     * Performs the safe answer operation.
     *
     * @param activeChatModel the active chat model value
     * @param answer the answer value
     * @param query the query value
     * @param observations the observations value
     * @param systemPrompt the system prompt value
     * @return the operation result
     */
    private String safeAnswer(ChatModel activeChatModel, String answer, String query, List<String> observations, String systemPrompt) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        return summarizeWithObservations(activeChatModel, query, systemPrompt, observations);
    }

    /**
     * Performs the summarize with observations operation.
     *
     * @param activeChatModel the active chat model value
     * @param query the query value
     * @param systemPrompt the system prompt value
     * @param observations the observations value
     * @return the operation result
     */
    private String summarizeWithObservations(ChatModel activeChatModel, String query, String systemPrompt, List<String> observations) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        prompt.append("Use the observations below and answer the user question in Chinese.\n");
        prompt.append("If any tool observation reports failure, explicitly state that this source was unavailable and do not treat it as evidence.\n");
        prompt.append("When both internal document and web search observations are available, separate internal document evidence from web verification evidence and explain conflicts instead of merging them silently.\n");
        prompt.append("If observations include web citation labels such as [网页1], append the matching label immediately after every sentence that relies on that web source.\n");
        prompt.append("Do not invent citations or cite URLs that are not listed in the observations.\n");
        if (observations.isEmpty()) {
            prompt.append("No external tool observation is available.\n");
        } else {
            observations.forEach(item -> prompt.append("- ").append(item).append("\n"));
        }
        prompt.append("\nUser question: ").append(query);
        return activeChatModel.chat(prompt.toString());
    }

    /**
     * Performs the review and revise answer operation.
     *
     * @param activeChatModel the active chat model value
     * @param query the query value
     * @param systemPrompt the system prompt value
     * @param observations the observations value
     * @param answer the answer value
     * @return the operation result
     */
    private AnswerReview reviewAndReviseAnswer(ChatModel activeChatModel,
                                               String query,
                                               String systemPrompt,
                                               List<String> observations,
                                               String answer) {
        if (answer == null || answer.isBlank()) {
            return new AnswerReview(REVIEW_REVISED, "", "Empty answer generated");
        }

        String raw = activeChatModel.chat(buildAnswerReviewPrompt(query, systemPrompt, observations, answer));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(extractJson(raw), Map.class);
            boolean accepted = booleanValue(payload.get("accepted"));
            String feedback = stringValue(payload.get("feedback"));
            String revisedAnswer = stringValue(payload.get("revisedAnswer"));
            if (accepted) {
                return new AnswerReview(REVIEW_ACCEPTED, answer, feedback);
            }
            if (revisedAnswer != null && !revisedAnswer.isBlank()) {
                return new AnswerReview(REVIEW_REVISED, revisedAnswer, feedback);
            }
            return new AnswerReview(REVIEW_ACCEPTED, answer, firstNonBlank(feedback, "Review rejected without revised answer"));
        } catch (Exception ex) {
            log.debug("Failed to parse answer review: {}", raw, ex);
            return new AnswerReview(REVIEW_ACCEPTED, answer, "Answer review unavailable");
        }
    }

    /**
     * Builds the answer review prompt.
     *
     * @param query the query value
     * @param systemPrompt the system prompt value
     * @param observations the observations value
     * @param answer the answer value
     * @return the built answer review prompt
     */
    private String buildAnswerReviewPrompt(String query,
                                           String systemPrompt,
                                           List<String> observations,
                                           String answer) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the final answer quality reviewer for an enterprise AI assistant.\n");
        prompt.append("Decide whether the candidate answer satisfies the user's actual formal request.\n");
        prompt.append("Reject answers that only point to documents/tools, summarize where information may be, or avoid giving the concrete requested result.\n");
        prompt.append("A good answer must directly address the user request, use the available observations as evidence, and clearly state missing evidence when the observations are insufficient.\n");
        prompt.append("Do not invent facts that are absent from the observations.\n");
        prompt.append("If both document_search and web_search observations are available, the answer must distinguish internal document evidence from web verification evidence and explicitly handle conflicts.\n");
        prompt.append("If an observation says a tool failed, the answer must not claim that the failed tool provided supporting evidence.\n");
        prompt.append("If observations include web citation labels such as [网页1], web-derived claims in the answer must keep the matching labels; reject and revise answers that omit those labels.\n");
        prompt.append("Do not remove citation markers that prove which web page supports a statement.\n");
        prompt.append("If the user's request is in Chinese, the revised answer must be in Chinese.\n\n");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("User request:\n").append(query).append("\n\n");
        prompt.append("Available observations:\n");
        if (observations == null || observations.isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            observations.forEach(item -> prompt.append("- ").append(item).append("\n"));
        }
        prompt.append("\nCandidate answer:\n").append(answer).append("\n\n");
        prompt.append("Respond with strict JSON only:\n");
        prompt.append("{\"accepted\":true|false,\"feedback\":\"brief reason\",\"revisedAnswer\":\"if rejected, provide the improved final answer; otherwise empty string\"}");
        return prompt.toString();
    }

    /**
     * Performs the record answer review operation.
     *
     * @param metadata the metadata value
     * @param review the review value
     */
    private void recordAnswerReview(Map<String, Object> metadata, AnswerReview review) {
        metadata.put("answerReviewStatus", review.status());
        if (review.feedback() != null && !review.feedback().isBlank()) {
            metadata.put("answerReviewFeedback", review.feedback());
        }
    }

    /**
     * Resolves the chat model.
     *
     * @param modelName the model name value
     * @return the resolved chat model
     */
    private ChatModel resolveChatModel(String modelName) {
        String normalized = normalizeModelName(modelName);
        if (normalized == null || normalized.equals(modelsConfig.getDefaultChatModel())) {
            return chatModel;
        }
        if (!"openai".equalsIgnoreCase(modelsConfig.getDefaultProvider())) {
            return chatModel;
        }
        return chatModelsByName.computeIfAbsent(normalized, this::buildOpenAiChatModel);
    }

    /**
     * Builds the open ai chat model.
     *
     * @param modelName the model name value
     * @return the built open ai chat model
     */
    private ChatModel buildOpenAiChatModel(String modelName) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .apiKey(modelsConfig.getOpenai().getApiKey())
            .baseUrl(modelsConfig.getOpenai().getBaseUrl())
            .modelName(modelName)
            .timeout(Duration.ofSeconds(modelsConfig.getOpenai().getTimeout()))
            .maxRetries(modelsConfig.getOpenai().getMaxRetries())
            .logRequests(true)
            .logResponses(true);
        HttpClientBuilder httpClientBuilder = resolveOpenAiHttpClientBuilder();
        if (httpClientBuilder != null) {
            builder.httpClientBuilder(httpClientBuilder);
        }
        return builder.build();
    }

    /**
     * Resolves the open ai http client builder.
     *
     * @return the resolved open ai http client builder
     */
    private HttpClientBuilder resolveOpenAiHttpClientBuilder() {
        ModelsConfig.ProxyConfig proxyConfig = modelsConfig.getOpenai().getProxy();
        if (proxyConfig == null || !proxyConfig.isEnabled()
            || proxyConfig.getHost() == null || proxyConfig.getHost().isBlank()
            || proxyConfig.getPort() == null || proxyConfig.getPort() <= 0) {
            return null;
        }
        if ("socks".equalsIgnoreCase(proxyConfig.getType())) {
            return null;
        }
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
            .proxy(ProxySelector.of(new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort())));
        return new JdkHttpClientBuilder().httpClientBuilder(httpClientBuilder);
    }

    /**
     * Performs the apply document search defaults operation.
     *
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @param boundDocumentIds the bound document ids value
     * @param boundDocumentTags the bound document tags value
     * @return the operation result
     */
    private Map<String, Object> applyDocumentSearchDefaults(String toolName,
                                                            Map<String, Object> arguments,
                                                            List<String> boundDocumentIds,
                                                            List<String> boundDocumentTags) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Collections.emptyMap() : arguments);
        if (!isDocumentSearchToolName(toolName)) {
            return values;
        }
        if (!boundDocumentIds.isEmpty() && !values.containsKey("document_ids")) {
            values.put("document_ids", boundDocumentIds);
        }
        if (!boundDocumentTags.isEmpty() && !values.containsKey("tags")) {
            values.put("tags", boundDocumentTags);
        }
        return values;
    }

    /**
     * Performs the apply tool defaults operation.
     *
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @param boundDocumentIds the bound document ids value
     * @param boundDocumentTags the bound document tags value
     * @param query the query value
     * @param webSearchResultLimit the web search result limit value
     * @return the operation result
     */
    private Map<String, Object> applyToolDefaults(String toolName,
                                                  Map<String, Object> arguments,
                                                  List<String> boundDocumentIds,
                                                  List<String> boundDocumentTags,
                                                  String query,
                                                  int webSearchResultLimit) {
        Map<String, Object> values = applyDocumentSearchDefaults(toolName, arguments, boundDocumentIds, boundDocumentTags);
        if (isDocumentSearchToolName(toolName) && !values.containsKey("query") && query != null && !query.isBlank()) {
            values.put("query", query);
        }
        if (!isWebSearchToolName(toolName)) {
            return values;
        }
        if (!values.containsKey("query") && query != null && !query.isBlank()) {
            values.put("query", query);
        }
        if (!values.containsKey("num_results")) {
            values.put("num_results", Math.max(1, Math.min(WEB_SEARCH_REFERENCE_LIMIT, webSearchResultLimit)));
        }
        return values;
    }

    /**
     * Performs the attributes with completed tools operation.
     *
     * @param runtimeAttributes the runtime attributes value
     * @param completedTools the completed tools value
     * @return the operation result
     */
    private Map<String, Object> attributesWithCompletedTools(Map<String, Object> runtimeAttributes,
                                                             Set<String> completedTools) {
        Map<String, Object> attributes = new LinkedHashMap<>(runtimeAttributes == null ? Map.of() : runtimeAttributes);
        Set<String> merged = new LinkedHashSet<>();
        Object existing = attributes.get("workflowCompletedTools");
        if (existing instanceof List<?> list) {
            list.stream().map(this::stringValue).filter(value -> value != null && !value.isBlank()).forEach(merged::add);
        } else if (existing instanceof String text && !text.isBlank()) {
            for (String item : text.split("[,;]")) {
                if (!item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        if (completedTools != null) {
            completedTools.stream().filter(value -> value != null && !value.isBlank()).forEach(merged::add);
        }
        if (!merged.isEmpty()) {
            attributes.put("workflowCompletedTools", new ArrayList<>(merged));
        }
        return attributes;
    }

    /**
     * Performs the remember completed workflow tool operation.
     *
     * @param completedTools the completed tools value
     * @param execution the execution value
     */
    private void rememberCompletedWorkflowTool(Set<String> completedTools, ToolCallExecution execution) {
        if (completedTools == null || execution == null || execution.trace() == null) {
            return;
        }
        if (execution.trace().isSuccess() && execution.trace().getToolName() != null && !execution.trace().getToolName().isBlank()) {
            completedTools.add(execution.trace().getToolName());
        }
    }

    /**
     * Performs the completed tools from traces operation.
     *
     * @param traces the traces value
     * @return the operation result
     */
    private Set<String> completedToolsFromTraces(List<InteractionToolTrace> traces) {
        Set<String> completed = new LinkedHashSet<>();
        if (traces == null || traces.isEmpty()) {
            return completed;
        }
        traces.stream()
            .filter(trace -> trace != null && trace.isSuccess())
            .map(InteractionToolTrace::getToolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .forEach(completed::add);
        return completed;
    }

    /**
     * Executes the pending confirmed tool.
     *
     * @param query the query value
     * @param conversationId the conversation id value
     * @param requestId the request id value
     * @param userId the user id value
     * @param tenantId the tenant id value
     * @param tools the tools value
     * @param runtimeAttributes the runtime attributes value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private ToolCallExecution executePendingConfirmedTool(String query,
                                                          String conversationId,
                                                          String requestId,
                                                          String userId,
                                                          String tenantId,
                                                          List<String> tools,
                                                          Map<String, Object> runtimeAttributes) {
        if (runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return null;
        }
        Map<String, Object> confirmation = asMap(runtimeAttributes.get("mcpConfirmation"));
        if (confirmation.isEmpty()) {
            return null;
        }
        Map<String, Object> pending = asMap(runtimeAttributes.get("mcpPendingToolExecution"));
        if (pending.isEmpty()) {
            return null;
        }
        String pendingToolName = normalizeToolName(stringValue(pending.get("toolName")), tools);
        if (pendingToolName == null || pendingToolName.isBlank() || !tools.contains(pendingToolName)) {
            return null;
        }
        Map<String, Object> arguments = asMap(pending.get("input"));
        Map<String, Object> executionPlan = asMap(pending.get("executionPlan"));
        return executeToolCall(
            pendingToolName,
            applyToolDefaults(pendingToolName, arguments, List.of(), List.of(), query, WEB_SEARCH_REFERENCE_LIMIT),
            conversationId,
            requestId,
            userId,
            tenantId,
            tools,
            executionPlan,
            runtimeAttributes
        );
    }

    /**
     * Executes the tool call.
     *
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @param conversationId the conversation id value
     * @param requestId the request id value
     * @param userId the user id value
     * @param tenantId the tenant id value
     * @param allowedTools the allowed tools value
     * @param plannerExecutionPlan the planner execution plan value
     * @param runtimeAttributes the runtime attributes value
     * @return the operation result
     */
    private ToolCallExecution executeToolCall(String toolName,
                                              Map<String, Object> arguments,
                                              String conversationId,
                                              String requestId,
                                              String userId,
                                              String tenantId,
                                              List<String> allowedTools,
                                              Map<String, Object> plannerExecutionPlan,
                                              Map<String, Object> runtimeAttributes) {
        Map<String, Object> safeArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Map<String, Object> attributes = new LinkedHashMap<>(runtimeAttributes == null ? Map.of() : runtimeAttributes);
        attributes.put("executionPlan", buildRuntimeExecutionPlan(toolName, safeArguments, plannerExecutionPlan));
        ToolInput toolInput = ToolInput.builder()
            .conversationId(conversationId)
            .requestId(requestId)
            .userId(userId)
            .parameters(safeArguments)
            .build();

        ToolRuntimeExecution execution = toolRuntimeService.execute(ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId(requestId)
            .conversationId(conversationId)
            .tenantId(tenantId)
            .userId(userId)
            .allowedTools(allowedTools == null ? List.of() : allowedTools)
            .toolInput(toolInput)
            .attributes(attributes)
            .build());
        ToolMetadata toolMetadata = execution.metadata();
        ToolOutput output = execution.output();
        String outputText = stringify(output.getData());
        InteractionToolTrace trace = execution.trace();

        String observation = output.isSuccess()
            ? buildToolSuccessObservation(toolName, output, outputText)
            : buildToolFailureObservation(toolName, output);
        return new ToolCallExecution(trace, observation);
    }

    /**
     * Builds the tool success observation.
     *
     * @param toolName the tool name value
     * @param output the output value
     * @param outputText the output text value
     * @return the built tool success observation
     */
    private String buildToolSuccessObservation(String toolName, ToolOutput output, String outputText) {
        Object data = output == null ? null : output.getData();
        if (isDocumentSearchToolName(toolName)) {
            return buildDocumentSearchObservation(toolName, output, data, outputText);
        }

        StringBuilder observation = new StringBuilder("Tool ")
            .append(toolName)
            .append(" succeeded.");
        if (!isWebSearchToolName(toolName)) {
            String message = output == null ? null : output.getMessage();
            if (message != null && !message.isBlank()) {
                observation.append(" Message: ").append(shortObservationText(message, 400));
            }
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }

        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }
        Map<String, Object> root = asMap(data);
        if (!root.isEmpty()) {
            observation.append("\nWeb search summary: query=")
                .append(firstNonBlank(stringValue(root.get("query")), "unknown"))
                .append(", provider=")
                .append(firstNonBlank(stringValue(root.get("provider")), stringValue(root.get("configuredProvider"))))
                .append(", results=")
                .append(firstNonBlank(stringValue(root.get("count")), "unknown"))
                .append(", referenceUrls=")
                .append(firstNonBlank(stringValue(root.get("reference_url_count")), "unknown"))
                .append(", pageExcerpts=")
                .append(firstNonBlank(stringValue(root.get("page_excerpt_count")), "unknown"))
                .append(", contentMode=")
                .append(firstNonBlank(stringValue(root.get("contentMode")), "unknown"))
                .append('.');
        }
        List<WebCitation> citations = extractWebCitations(data);
        if (citations.isEmpty()) {
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }
        observation.append("\nWeb citation map. Use these labels in the final answer when relying on web search evidence:\n");
        for (int i = 0; i < citations.size(); i++) {
            WebCitation citation = citations.get(i);
            observation.append("[网页").append(i + 1).append("] ")
                .append(firstNonBlank(citation.title(), citation.url()))
                .append(" - ")
                .append(citation.url());
            if (citation.snippet() != null && !citation.snippet().isBlank()) {
                observation.append(" - ").append(citation.snippet());
            }
            observation.append("\n");
        }
        observation.append("Citation rule: append the matching [网页N] label immediately after any sentence that uses facts from that page.");
        return normalizeWebCitationLabels(observation.toString());
    }

    /**
     * Builds the document search observation.
     *
     * @param toolName the tool name value
     * @param output the output value
     * @param data the data value
     * @param outputText the output text value
     * @return the built document search observation
     */
    private String buildDocumentSearchObservation(String toolName, ToolOutput output, Object data, String outputText) {
        StringBuilder observation = new StringBuilder("Tool ")
            .append(toolName)
            .append(" succeeded.");
        String message = output == null ? null : output.getMessage();
        if (message != null && !message.isBlank()) {
            observation.append(" Message: ").append(shortObservationText(message, 400));
        }

        Map<String, Object> root = asMap(data);
        if (!root.isEmpty()) {
            List<Map<String, Object>> results = new ArrayList<>();
            addCandidateList(results, root.get("results"));
            addCandidateList(results, root.get("items"));
            addCandidateList(results, root.get("records"));
            observation.append("\nDocument search summary: total=")
                .append(firstNonBlank(
                    firstNonBlank(stringValue(root.get("total")), stringValue(root.get("totalCount"))),
                    firstNonBlank(stringValue(root.get("count")), "unknown")
                ))
                .append(", returned=")
                .append(results.size())
                .append(", contentMode=")
                .append(firstNonBlank(stringValue(root.get("contentMode")), "unknown"))
                .append('.');
        }

        List<DocumentEvidence> evidence = extractDocumentEvidence(data);
        if (evidence.isEmpty()) {
            String summary = shortObservationText(outputText, 600);
            if (summary != null && !summary.isBlank()) {
                observation.append(" Output summary: ").append(summary);
            }
            return observation.toString();
        }

        observation.append("\nDocument evidence snippets:\n");
        for (int i = 0; i < evidence.size(); i++) {
            DocumentEvidence item = evidence.get(i);
            observation.append("[\u6587\u6863").append(i + 1).append("] ")
                .append(firstNonBlank(item.title(), "Untitled document"));
            if (item.docId() != null && !item.docId().isBlank()) {
                observation.append(" (docId=").append(item.docId()).append(")");
            }
            if (item.snippet() != null && !item.snippet().isBlank()) {
                observation.append(" - ").append(item.snippet());
            }
            observation.append("\n");
        }
        return observation.toString();
    }

    /**
     * Builds the tool failure observation.
     *
     * @param toolName the tool name value
     * @param output the output value
     * @return the built tool failure observation
     */
    private String buildToolFailureObservation(String toolName, ToolOutput output) {
        String error = firstNonBlank(output.getErrorMessage(), output.getExceptionType());
        if (error == null || error.isBlank()) {
            error = "unknown error";
        }
        return "Tool " + toolName + " failed. Error: " + error
            + ". Evidence from this tool is unavailable; the final answer must explicitly mention this limitation and must not claim successful verification from this tool.";
    }

    /**
     * Builds the runtime execution plan.
     *
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @param plannerExecutionPlan the planner execution plan value
     * @return the built runtime execution plan
     */
    private Map<String, Object> buildRuntimeExecutionPlan(String toolName,
                                                          Map<String, Object> arguments,
                                                          Map<String, Object> plannerExecutionPlan) {
        Map<String, Object> plan = new LinkedHashMap<>(plannerExecutionPlan == null ? Map.of() : plannerExecutionPlan);
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        plan.putIfAbsent("intent", firstNonBlank(stringValue(plan.get("intent")), "Use tool to satisfy the user request"));
        plan.put("tool", firstNonBlank(stringValue(plan.get("tool")), toolName));
        plan.put("operation_type", firstNonBlank(
            firstNonBlank(stringValue(plan.get("operation_type")), stringValue(plan.get("operationType"))),
            metadata == null ? "read" : firstNonBlank(metadata.getOperationType(), "read")
        ));
        plan.put("risk_level", firstNonBlank(
            firstNonBlank(stringValue(plan.get("risk_level")), stringValue(plan.get("riskLevel"))),
            metadata == null ? "low" : firstNonBlank(metadata.getRiskLevel(), "low")
        ));
        plan.put("parameters", arguments == null ? Map.of() : new LinkedHashMap<>(arguments));
        plan.putIfAbsent("reason", firstNonBlank(stringValue(plan.get("reason")), "Planner selected " + toolName));
        return plan;
    }

    /**
     * Returns whether should require document web verification.
     *
     * @param tools the tools value
     * @param documentSearchTool the document search tool value
     * @param verificationWebSearchTool the verification web search tool value
     * @param documentIds the document ids value
     * @param documentTags the document tags value
     * @return whether the condition is satisfied
     */
    private boolean shouldRequireDocumentWebVerification(List<String> tools,
                                                         String documentSearchTool,
                                                         String verificationWebSearchTool,
                                                         List<String> documentIds,
                                                         List<String> documentTags) {
        return tools != null
            && documentSearchTool != null
            && tools.contains(documentSearchTool)
            && verificationWebSearchTool != null
            && (!documentIds.isEmpty() || !documentTags.isEmpty());
    }

    /**
     * Resolves the document search tool.
     *
     * @param tools the tools value
     * @return the resolved document search tool
     */
    private String resolveDocumentSearchTool(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        if (tools.contains(DOCUMENT_SEARCH_TOOL)) {
            return DOCUMENT_SEARCH_TOOL;
        }
        return tools.stream()
            .filter(this::isDocumentSearchToolName)
            .findFirst()
            .orElse(null);
    }

    /**
     * Resolves the verification web search tool.
     *
     * @param tools the tools value
     * @return the resolved verification web search tool
     */
    private String resolveVerificationWebSearchTool(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        if (tools.contains(WEB_SEARCH_TOOL)) {
            return WEB_SEARCH_TOOL;
        }
        return tools.stream()
            .filter(this::isWebSearchToolName)
            .findFirst()
            .orElse(null);
    }

    /**
     * Returns whether missing document web verification.
     *
     * @param traces the traces value
     * @param documentSearchTool the document search tool value
     * @param verificationWebSearchTool the verification web search tool value
     * @return whether the condition is satisfied
     */
    private boolean missingDocumentWebVerification(List<InteractionToolTrace> traces,
                                                   String documentSearchTool,
                                                   String verificationWebSearchTool) {
        return !hasToolTrace(traces, documentSearchTool) || !hasToolTrace(traces, verificationWebSearchTool);
    }

    /**
     * Returns whether has tool trace.
     *
     * @param traces the traces value
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean hasToolTrace(List<InteractionToolTrace> traces, String toolName) {
        if (traces == null || traces.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        return traces.stream()
            .anyMatch(trace -> trace != null && sameToolName(toolName, trace.getToolName()));
    }

    /**
     * Performs the missing mandatory tools operation.
     *
     * @param mandatoryTools the mandatory tools value
     * @param traces the traces value
     * @return the operation result
     */
    private List<String> missingMandatoryTools(List<String> mandatoryTools, List<InteractionToolTrace> traces) {
        return normalizeList(mandatoryTools).stream()
            .filter(toolName -> !hasToolTrace(traces, toolName))
            .toList();
    }

    /**
     * Runs the configured startup logic.
     *
     * @param traces the traces value
     * @param observations the observations value
     * @param query the query value
     * @param conversationId the conversation id value
     * @param requestId the request id value
     * @param userId the user id value
     * @param tenantId the tenant id value
     * @param tools the tools value
     * @param documentSearchTool the document search tool value
     * @param documentIds the document ids value
     * @param documentTags the document tags value
     * @param webSearchResultLimit the web search result limit value
     * @param verificationWebSearchTool the verification web search tool value
     * @param metadata the metadata value
     * @param runtimeAttributes the runtime attributes value
     */
    private void runMissingDocumentWebVerification(List<InteractionToolTrace> traces,
                                                   List<String> observations,
                                                   String query,
                                                   String conversationId,
                                                   String requestId,
                                                   String userId,
                                                   String tenantId,
                                                   List<String> tools,
                                                   String documentSearchTool,
                                                   List<String> documentIds,
                                                   List<String> documentTags,
                                                   int webSearchResultLimit,
                                                   String verificationWebSearchTool,
                                                   Map<String, Object> metadata,
                                                   Map<String, Object> runtimeAttributes) {
        List<String> fallbackTools = new ArrayList<>();
        if (!hasToolTrace(traces, documentSearchTool)) {
            fallbackTools.add(documentSearchTool);
        }
        if (!hasToolTrace(traces, verificationWebSearchTool)) {
            fallbackTools.add(verificationWebSearchTool);
        }
        if (fallbackTools.isEmpty()) {
            return;
        }

        metadata.put("documentWebVerificationFallbackTools", fallbackTools);
        for (String fallbackTool : fallbackTools) {
            if (fallbackTool == null || !tools.contains(fallbackTool)) {
                continue;
            }
            Map<String, Object> fallbackArguments = applyToolDefaults(
                fallbackTool,
                defaultToolArguments(fallbackTool, query, webSearchResultLimit),
                documentIds,
                documentTags,
                query,
                webSearchResultLimit
            );
            ToolCallExecution execution = executeToolCall(
                fallbackTool,
                fallbackArguments,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                Map.of(),
                attributesWithCompletedTools(runtimeAttributes, completedToolsFromTraces(traces))
            );
            traces.add(execution.trace());
            observations.add("Document-web verification fallback " + execution.observation());
            runtimeAttributes = attributesWithCompletedTools(runtimeAttributes, completedToolsFromTraces(traces));
            if (isConfirmationRequired(execution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return;
            }
        }
    }

    /**
     * Returns whether cancellation check.
     *
     * @param runtimeAttributes the runtime attributes value
     * @return whether the condition is satisfied
     */
    private BooleanSupplier cancellationCheck(Map<String, Object> runtimeAttributes) {
        Object value = runtimeAttributes == null ? null : runtimeAttributes.get("__agentCancellation");
        if (value instanceof BooleanSupplier supplier) {
            return supplier;
        }
        return () -> Thread.currentThread().isInterrupted();
    }

    /**
     * Performs the check cancelled operation.
     *
     * @param cancellationCheck the cancellation check value
     */
    private void checkCancelled(BooleanSupplier cancellationCheck) {
        if (Thread.currentThread().isInterrupted() || (cancellationCheck != null && cancellationCheck.getAsBoolean())) {
            throw new CancellationException("Agent task cancelled");
        }
    }

    /**
     * Returns whether is confirmation required.
     *
     * @param execution the execution value
     * @return whether the condition is satisfied
     */
    private boolean isConfirmationRequired(ToolCallExecution execution) {
        if (execution == null || execution.trace() == null || execution.trace().getRuntimeMetadata() == null) {
            return false;
        }
        Object outcome = execution.trace().getRuntimeMetadata().get("outcome");
        return "confirmation_required".equalsIgnoreCase(String.valueOf(outcome));
    }

    /**
     * Performs the default tool arguments operation.
     *
     * @param toolName the tool name value
     * @param query the query value
     * @param webSearchResultLimit the web search result limit value
     * @return the operation result
     */
    private Map<String, Object> defaultToolArguments(String toolName, String query, int webSearchResultLimit) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        if ("calculator".equals(toolName)) {
            return Map.of("expression", query);
        }
        if (isWebSearchToolName(toolName)) {
            return Map.of("query", query, "num_results", Math.max(1, Math.min(WEB_SEARCH_REFERENCE_LIMIT, webSearchResultLimit)));
        }
        if (toolName != null && (toolName.startsWith("mcp_") || isDocumentSearchToolName(toolName))) {
            return Map.of("query", query);
        }
        return Map.of("input", query);
    }

    /**
     * Performs the extract web citations operation.
     *
     * @param data the data value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private List<WebCitation> extractWebCitations(Object data) {
        Map<String, Object> root = asMap(data);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidateList(candidates, root.get("results"));
        addCandidateList(candidates, root.get("items"));
        addCandidateList(candidates, root.get("organic_results"));
        addCandidateList(candidates, root.get("webPages"));
        addCandidateList(candidates, root.get("pageExcerpts"));
        addCandidateList(candidates, root.get("evidenceSnippets"));

        Map<String, WebCitation> byUrl = new LinkedHashMap<>();
        for (Map<String, Object> item : candidates) {
            String url = firstNonBlank(
                stringValue(item.get("url")),
                firstNonBlank(stringValue(item.get("link")), stringValue(item.get("href")))
            );
            if (url == null || url.isBlank() || byUrl.containsKey(url)) {
                continue;
            }
            byUrl.put(url, new WebCitation(
                url,
                firstNonBlank(
                    stringValue(item.get("title")),
                    firstNonBlank(stringValue(item.get("name")), stringValue(item.get("source")))
                ),
                shortText(firstNonBlank(
                    stringValue(item.get("snippet")),
                    firstNonBlank(
                        stringValue(item.get("excerpt")),
                        firstNonBlank(
                            stringValue(item.get("pageExcerpt")),
                            firstNonBlank(stringValue(item.get("contentExcerpt")), stringValue(item.get("summary")))
                        )
                    )
                ))
            ));
        }

        Object referenceUrlsValue = root.get("reference_urls");
        if (!(referenceUrlsValue instanceof List<?> referenceUrls) || referenceUrls.isEmpty()) {
            return byUrl.values().stream().limit(WEB_SEARCH_REFERENCE_LIMIT).toList();
        }
        List<WebCitation> citations = new ArrayList<>();
        for (Object value : referenceUrls) {
            if (citations.size() >= WEB_SEARCH_REFERENCE_LIMIT) {
                break;
            }
            String url = stringValue(value);
            if (url == null || url.isBlank()) {
                continue;
            }
            WebCitation matched = byUrl.get(url);
            citations.add(matched == null ? new WebCitation(url, url, null) : matched);
        }
        return citations;
    }

    /**
     * Performs the extract document evidence operation.
     *
     * @param data the data value
     * @return the operation result
     */
    private List<DocumentEvidence> extractDocumentEvidence(Object data) {
        Map<String, Object> root = asMap(data);
        if (root.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidateList(candidates, root.get("evidenceSnippets"));
        addCandidateList(candidates, root.get("results"));
        addCandidateList(candidates, root.get("items"));
        addCandidateList(candidates, root.get("records"));

        List<DocumentEvidence> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : candidates) {
            if (evidence.size() >= WEB_SEARCH_REFERENCE_LIMIT) {
                break;
            }
            String docId = firstNonBlank(
                firstNonBlank(stringValue(item.get("docId")), stringValue(item.get("documentId"))),
                firstNonBlank(stringValue(item.get("id")), stringValue(item.get("fileId")))
            );
            String title = firstNonBlank(
                firstNonBlank(stringValue(item.get("title")), stringValue(item.get("name"))),
                firstNonBlank(stringValue(item.get("filename")), stringValue(item.get("source")))
            );
            String snippet = shortText(firstNonBlank(
                stringValue(item.get("excerpt")),
                firstNonBlank(
                    stringValue(item.get("contentExcerpt")),
                    firstNonBlank(stringValue(item.get("snippet")), stringValue(item.get("summary")))
                )
            ));
            if ((title == null || title.isBlank()) && (snippet == null || snippet.isBlank())) {
                continue;
            }
            String key = firstNonBlank(docId, "") + "|" + firstNonBlank(title, "") + "|" + firstNonBlank(snippet, "");
            if (!seen.add(key)) {
                continue;
            }
            evidence.add(new DocumentEvidence(docId, title, snippet));
        }
        return evidence;
    }

    /**
     * Performs the as map operation.
     *
     * @param data the data value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (data instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (Exception ex) {
                return Map.of();
            }
        }
        return Map.of();
    }

    /**
     * Adds the candidate list.
     *
     * @param candidates the candidates value
     * @param value the value value
     */
    @SuppressWarnings("unchecked")
    private void addCandidateList(List<Map<String, Object>> candidates, Object value) {
        if (!(value instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                candidates.add((Map<String, Object>) map);
            }
        }
    }

    /**
     * Returns whether is web search tool name.
     *
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean isWebSearchToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains("web_search");
    }

    /**
     * Returns whether is document search tool name.
     *
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean isDocumentSearchToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains("document_search");
    }

    /**
     * Performs the short text operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String shortText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180);
    }

    /**
     * Performs the short observation text operation.
     *
     * @param value the value value
     * @param maxChars the max chars value
     * @return the operation result
     */
    private String shortObservationText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    /**
     * Normalizes the web citation labels.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeWebCitationLabels(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replace("[\u7f03\u6226\u3009", "[\u7f51\u9875");
    }

    /**
     * Resolves the workflow mandatory tools.
     *
     * @param tools the tools value
     * @param runtimeAttributes the runtime attributes value
     * @return the resolved workflow mandatory tools
     */
    private List<String> resolveWorkflowMandatoryTools(List<String> tools, Map<String, Object> runtimeAttributes) {
        if (tools == null || tools.isEmpty() || runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> workflow = asMap(runtimeAttributes.get("mcpWorkflow"));
        if (workflow.isEmpty()) {
            return List.of();
        }
        Object enabled = workflow.get("enabled");
        if (enabled instanceof Boolean bool && !bool) {
            return List.of();
        }
        Object steps = workflow.get("steps");
        if (!(steps instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<WorkflowToolStep> requiredSteps = new ArrayList<>();
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
            if (Boolean.FALSE.equals(required)) {
                index++;
                continue;
            }
            for (String stepTool : stepTools) {
                String resolved = normalizeToolName(stepTool, tools);
                if (resolved != null && tools.contains(resolved)) {
                    requiredSteps.add(new WorkflowToolStep(firstInteger(firstObject(step, "step", "order"), index), resolved));
                }
            }
            index++;
        }

        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        requiredSteps.stream()
            .sorted(Comparator.comparingInt(WorkflowToolStep::order))
            .map(WorkflowToolStep::toolName)
            .forEach(tool -> ordered.put(tool, Boolean.TRUE));
        return new ArrayList<>(ordered.keySet());
    }

    /**
     * Resolves the mandatory tool candidates.
     *
     * @param tools the tools value
     * @param requiredToolNames the required tool names value
     * @param requireBoundToolCall the require bound tool call value
     * @return the resolved mandatory tool candidates
     */
    private List<String> resolveMandatoryToolCandidates(List<String> tools,
                                                        List<String> requiredToolNames,
                                                        boolean requireBoundToolCall) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<String> requiredTools = normalizeList(requiredToolNames).stream()
            .map(toolName -> normalizeToolName(toolName, tools))
            .filter(tools::contains)
            .distinct()
            .toList();
        if (!requiredTools.isEmpty()) {
            return requiredTools;
        }
        return List.of();
    }

    /**
     * Performs the with document web verification mandatory tools operation.
     *
     * @param mandatoryTools the mandatory tools value
     * @param documentSearchTool the document search tool value
     * @param verificationWebSearchTool the verification web search tool value
     * @return the operation result
     */
    private List<String> withDocumentWebVerificationMandatoryTools(List<String> mandatoryTools,
                                                                   String documentSearchTool,
                                                                   String verificationWebSearchTool) {
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        if (documentSearchTool != null && !documentSearchTool.isBlank()) {
            ordered.put(documentSearchTool, Boolean.TRUE);
        }
        if (verificationWebSearchTool != null && !verificationWebSearchTool.isBlank()) {
            ordered.put(verificationWebSearchTool, Boolean.TRUE);
        }
        normalizeList(mandatoryTools).forEach(toolName -> ordered.put(toolName, Boolean.TRUE));
        return new ArrayList<>(ordered.keySet());
    }

    /**
     * Normalizes the tool name.
     *
     * @param toolName the tool name value
     * @param availableTools the available tools value
     * @return the operation result
     */
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

    /**
     * Normalizes the known tool alias.
     *
     * @param toolName the tool name value
     * @return the operation result
     */
    private String normalizeKnownToolAlias(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim();
        String key = normalized.toLowerCase(Locale.ROOT);
        if (DOCUMENT_SEARCH_TOOL.equals(key)) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (WEB_SEARCH_TOOL.equals(key)) {
            return WEB_SEARCH_TOOL;
        }
        return normalized;
    }

    /**
     * Returns whether contains tool name.
     *
     * @param tools the tools value
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean containsToolName(List<String> tools, String toolName) {
        return tools != null && tools.stream().anyMatch(candidate -> sameToolName(candidate, toolName));
    }

    /**
     * Returns whether same tool name.
     *
     * @param first the first value
     * @param second the second value
     * @return whether the condition is satisfied
     */
    private boolean sameToolName(String first, String second) {
        String left = toolSemanticKey(first);
        String right = toolSemanticKey(second);
        return left != null && left.equals(right);
    }

    /**
     * Converts the value to ol semantic key.
     *
     * @param toolName the tool name value
     * @return the converted ol semantic key
     */
    private String toolSemanticKey(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(DOCUMENT_SEARCH_TOOL)) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (normalized.contains(WEB_SEARCH_TOOL)) {
            return WEB_SEARCH_TOOL;
        }
        return normalized;
    }

    /**
     * Returns whether is mcp tool.
     *
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean isMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata != null) {
            if (metadata.getCategories() != null && metadata.getCategories().stream().anyMatch("mcp"::equalsIgnoreCase)) {
                return true;
            }
            if (metadata.getTags() != null && metadata.getTags().stream().anyMatch("mcp"::equalsIgnoreCase)) {
                return true;
            }
            if (metadata.getAuthor() != null && metadata.getAuthor().trim().startsWith("MCP:")) {
                return true;
            }
        }
        return toolName.startsWith("mcp_");
    }

    /**
     * Normalizes the list.
     *
     * @param values the values value
     * @return the operation result
     */
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

    /**
     * Performs the string list operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            List<String> values = new ArrayList<>();
            for (String item : text.split("[,;\\n]")) {
                if (!item.isBlank()) {
                    values.add(item.trim());
                }
            }
            return values.stream().distinct().toList();
        }
        return List.of();
    }

    /**
     * Normalizes the model name.
     *
     * @param modelName the model name value
     * @return the operation result
     */
    private String normalizeModelName(String modelName) {
        return modelName == null || modelName.isBlank() ? null : modelName.trim();
    }

    /**
     * Performs the stringify operation.
     *
     * @param data the data value
     * @return the operation result
     */
    private String stringify(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    /**
     * Performs the string value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Performs the preview operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    /**
     * Returns whether boolean value.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Returns whether boolean object.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private Boolean booleanObject(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Performs the first object operation.
     *
     * @param values the values value
     * @param keys the keys value
     * @return the operation result
     */
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

    /**
     * Performs the first integer operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private int firstInteger(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * Performs the first non blank operation.
     *
     * @param first the first value
     * @param second the second value
     * @return the operation result
     */
    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    /**
     * Resolves the display name.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @return the resolved display name
     */
    private String resolveDisplayName(String toolName, ToolMetadata metadata) {
        if (metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            return metadata.getTitle().trim();
        }
        return toolName;
    }

    /**
     * Resolves the service id.
     *
     * @param metadata the metadata value
     * @return the resolved service id
     */
    private String resolveServiceId(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return null;
        }
        Object value = metadata.getMetadata().get("serviceId");
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Resolves the service name.
     *
     * @param metadata the metadata value
     * @return the resolved service name
     */
    private String resolveServiceName(ToolMetadata metadata) {
        if (metadata == null || metadata.getAuthor() == null || metadata.getAuthor().isBlank()) {
            return null;
        }
        String author = metadata.getAuthor().trim();
        if (author.startsWith("MCP:")) {
            return author.substring(4).trim();
        }
        return author;
    }

    private record AgentDecision(
        String action,
        String toolName,
        Map<String, Object> arguments,
        String answer,
        String reason,
        Map<String, Object> executionPlan
    ) {
    }

    private record ToolCallExecution(
        InteractionToolTrace trace,
        String observation
    ) {
    }

    private record AnswerReview(
        String status,
        String answer,
        String feedback
    ) {
    }

    private record WebCitation(
        String url,
        String title,
        String snippet
    ) {
    }

    private record DocumentEvidence(
        String docId,
        String title,
        String snippet
    ) {
    }

    private record WorkflowToolStep(
        int order,
        String toolName
    ) {
    }

    public record AgentExecutionResult(
        String answer,
        List<InteractionToolTrace> toolTraces,
        Map<String, Object> metadata
    ) {
    }
}
