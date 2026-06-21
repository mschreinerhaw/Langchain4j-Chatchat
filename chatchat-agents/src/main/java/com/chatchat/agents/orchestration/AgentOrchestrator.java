package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentObservationPipeline;
import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRunStore;
import com.chatchat.agents.runtime.DefaultAgentAnswerReviewer;
import com.chatchat.agents.runtime.DefaultAgentObservationPipeline;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlanDagConverter;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.InterpretationPlanRewriter;
import com.chatchat.agents.runtime.plan.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Agent orchestrator with tool planning and execution loop.
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private static final int MAX_STEPS = 3;
    private static final int WEB_SEARCH_REFERENCE_LIMIT = 10;
    private static final String AGENT_CANCELLATION_ATTRIBUTE = "__agentCancellation";
    private static final String AGENT_MAX_STEPS_ATTRIBUTE = "__agentMaxSteps";
    private static final String AGENT_MAX_TOOL_CALLS_ATTRIBUTE = "__agentMaxToolCalls";
    private static final String AGENT_TIMEOUT_MS_ATTRIBUTE = "__agentTimeoutMs";
    private static final String AGENT_DEADLINE_AT_ATTRIBUTE = "__agentDeadlineAt";
    private static final String AGENT_RUN_ID_ATTRIBUTE = "__agentRunId";
    private static final String FINAL = "final";
    private static final String TOOL = "tool";
    private static final String WORKFLOW_PROBLEM_SOLVING = "agent_problem_solving";
    private final ToolRegistry toolRegistry;
    private final ToolRuntimeService toolRuntimeService;
    private final ObjectMapper objectMapper;
    private final EvidenceTrustEvaluator evidenceTrustEvaluator;
    private final AgentRunStore runStore;
    private final AgentObservationPipeline observationPipeline;
    private final AgentWorkflowDecisionEngine workflowDecisionEngine = new AgentWorkflowDecisionEngine();
    private final AgentRuntimeGuard runtimeGuard = new AgentRuntimeGuard(
        MAX_STEPS,
        AGENT_CANCELLATION_ATTRIBUTE,
        AGENT_MAX_STEPS_ATTRIBUTE,
        AGENT_MAX_TOOL_CALLS_ATTRIBUTE,
        AGENT_TIMEOUT_MS_ATTRIBUTE,
        AGENT_DEADLINE_AT_ATTRIBUTE
    );
    private final AgentPlanner planner;
    private final AgentRunResultAdapter runResultAdapter;
    private final ToolObservationBuilder toolObservationBuilder;
    private final AgentChatModelResolver chatModelResolver;
    private final AgentToolNameResolver toolNames;
    private final AgentToolArgumentResolver toolArguments;
    private final AgentWorkflowToolResolver workflowTools;
    private final AgentWorkflowStateTracker workflowStateTracker = new AgentWorkflowStateTracker();
    private final AgentAnswerFinalizer answerFinalizer;
    private final InterpretationPlanStore interpretationPlanStore;
    private final InterpretationPlanDagConverter interpretationPlanDagConverter = new InterpretationPlanDagConverter();

    public AgentOrchestrator(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig) {
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            new EvidenceTrustEvaluator(), new InMemoryAgentRunStore(), new DefaultAgentObservationPipeline(),
            new DefaultAgentAnswerReviewer(objectMapper));
    }

    public AgentOrchestrator(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig,
                             EvidenceTrustEvaluator evidenceTrustEvaluator) {
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, new InMemoryAgentRunStore(), new DefaultAgentObservationPipeline(),
            new DefaultAgentAnswerReviewer(objectMapper));
    }

    public AgentOrchestrator(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig,
                             EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore) {
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, new DefaultAgentObservationPipeline(),
            new DefaultAgentAnswerReviewer(objectMapper));
    }

    public AgentOrchestrator(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig,
                             EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore,
                             AgentObservationPipeline observationPipeline) {
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, observationPipeline, new DefaultAgentAnswerReviewer(objectMapper));
    }

    public AgentOrchestrator(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig,
                             EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore,
                             AgentObservationPipeline observationPipeline,
                             AgentAnswerReviewer answerReviewer) {
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, observationPipeline, answerReviewer, null);
    }

    @Autowired
    public AgentOrchestrator(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig,
                             EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore,
                             AgentObservationPipeline observationPipeline,
                             AgentAnswerReviewer answerReviewer,
                             InterpretationPlanStore interpretationPlanStore) {
        this.toolRegistry = toolRegistry;
        this.toolRuntimeService = toolRuntimeService;
        this.objectMapper = objectMapper;
        this.evidenceTrustEvaluator = evidenceTrustEvaluator == null ? new EvidenceTrustEvaluator() : evidenceTrustEvaluator;
        this.runStore = runStore == null ? new InMemoryAgentRunStore() : runStore;
        this.observationPipeline = observationPipeline == null ? new DefaultAgentObservationPipeline() : observationPipeline;
        AgentAnswerReviewer resolvedAnswerReviewer = answerReviewer == null ? new DefaultAgentAnswerReviewer(objectMapper) : answerReviewer;
        this.planner = new AgentPlanner(toolRegistry, objectMapper);
        this.runResultAdapter = new AgentRunResultAdapter(this.runStore, this.observationPipeline);
        this.toolObservationBuilder = new ToolObservationBuilder(this.evidenceTrustEvaluator);
        this.chatModelResolver = new AgentChatModelResolver(chatModel, modelsConfig);
        this.toolNames = new AgentToolNameResolver();
        this.toolArguments = new AgentToolArgumentResolver(this.toolNames, WEB_SEARCH_REFERENCE_LIMIT);
        this.workflowTools = new AgentWorkflowToolResolver(this.toolNames);
        this.answerFinalizer = new AgentAnswerFinalizer(resolvedAnswerReviewer, this.runtimeGuard);
        this.interpretationPlanStore = interpretationPlanStore == null && this.runStore instanceof InterpretationPlanStore store
            ? store
            : interpretationPlanStore;
    }

    /**
     * Executes an agent run through the stable runtime request/result contract.
     *
     * @param request the agent run request
     * @return the agent run result
     */
    public AgentRunResult execute(AgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent run request is required");
        }
        AgentRun run = runStore.start(request);
        try {
            AgentExecutionResult result = executeAgent(
                request.getQuery(),
                request.getTenantId(),
                request.getAvailableTools(),
                request.getSystemPrompt(),
                request.getModelName(),
                request.getBoundDocumentIds(),
                request.getBoundDocumentTags(),
                request.getSkillId(),
                request.getRequestId(),
                request.getConversationId(),
                request.getUserId(),
                request.getWebSearchResultLimit(),
                request.getRequiredToolNames(),
                request.isRequireBoundToolCall(),
                runtimeAttributesFor(request)
            );
            AgentRunResult runtimeResult = runResultAdapter.toAgentRunResult(run.runId(), result);
            AgentRun completed = runStore.complete(run.runId(), runtimeResult);
            return runtimeResult.withStatusAndEvents(completed.status(), completed.events());
        } catch (CancellationException ex) {
            AgentRun cancelled = runStore.cancel(run.runId(), ex.getMessage());
            return cancelledAgentRunResult(cancelled);
        } catch (RuntimeException ex) {
            AgentRun failed = runStore.fail(run.runId(), ex);
            return failedAgentRunResult(failed);
        }
    }

    private AgentRunResult cancelledAgentRunResult(AgentRun run) {
        return AgentRunResult.builder()
            .runId(run.runId())
            .status(AgentRunStatus.CANCELLED)
            .answer("")
            .stopReason("cancelled")
            .errorMessage(run.errorMessage())
            .events(run.events())
            .metadata(run.metadata())
            .build();
    }

    private AgentRunResult failedAgentRunResult(AgentRun run) {
        return AgentRunResult.builder()
            .runId(run.runId())
            .status(AgentRunStatus.FAILED)
            .answer("")
            .stopReason("failed")
            .errorMessage(run.errorMessage())
            .events(run.events())
            .metadata(run.metadata())
            .build();
    }

    private Map<String, Object> runtimeAttributesFor(AgentRunRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (request.getAttributes() != null) {
            attributes.putAll(request.getAttributes());
        }
        if (request.getRunId() != null && !request.getRunId().isBlank()) {
            attributes.put(AGENT_RUN_ID_ATTRIBUTE, request.getRunId());
        }
        if (request.getMaxSteps() != null) {
            attributes.put(AGENT_MAX_STEPS_ATTRIBUTE, request.getMaxSteps());
        }
        if (request.getMaxToolCalls() != null) {
            attributes.put(AGENT_MAX_TOOL_CALLS_ATTRIBUTE, request.getMaxToolCalls());
        }
        if (request.getTimeoutMs() != null) {
            attributes.put(AGENT_TIMEOUT_MS_ATTRIBUTE, request.getTimeoutMs());
        }
        return runtimeGuard.attributesWithDeadline(attributes);
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
        Map<String, Object> requestRuntimeAttributes = runtimeGuard.attributesWithDeadline(runtimeAttributes);
        BooleanSupplier cancellationCheck = runtimeGuard.cancellationCheck(requestRuntimeAttributes);
        int maxSteps = runtimeGuard.maxSteps(requestRuntimeAttributes);
        int maxToolCalls = runtimeGuard.maxToolCalls(requestRuntimeAttributes);
        List<String> documentIds = normalizeList(boundDocumentIds);
        List<String> documentTags = normalizeList(boundDocumentTags);
        String documentSearchTool = toolNames.resolveDocumentSearchTool(tools);
        String verificationWebSearchTool = toolNames.resolveVerificationWebSearchTool(tools);
        boolean requireDocumentWebVerification = workflowTools.shouldRequireDocumentWebVerification(
            tools,
            documentSearchTool,
            verificationWebSearchTool,
            documentIds,
            documentTags
        );
        WorkflowMandatoryResolution workflowMandatoryResolution = workflowDecisionEngine.resolveWorkflowMandatoryTools(
            tools,
            requestRuntimeAttributes,
            query
        );
        List<String> workflowMandatoryTools = workflowMandatoryResolution.tools();
        List<String> mandatoryTools = workflowMandatoryTools.isEmpty()
            ? workflowTools.resolveMandatoryToolCandidates(tools, requiredToolNames)
            : workflowMandatoryTools;
        if (requireDocumentWebVerification) {
            mandatoryTools = workflowTools.withDocumentWebVerificationMandatoryTools(mandatoryTools, documentSearchTool, verificationWebSearchTool);
        }
        if (!mandatoryTools.isEmpty()) {
            maxSteps = Math.max(maxSteps, mandatoryTools.size() + 1);
        }
        boolean requireToolBeforeFinal = !mandatoryTools.isEmpty();
        ChatModel activeChatModel = chatModelResolver.resolveChatModel(modelName);
        List<InteractionToolTrace> traces = new ArrayList<>();
        List<String> observations = runResultAdapter.runtimeObservationList(stringValue(requestRuntimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Map<String, Object>> plannerSteps = new ArrayList<>();
        metadata.put("agentRunId", stringValue(requestRuntimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)));
        metadata.put("skillId", skillId == null ? "general" : skillId);
        metadata.put("modelName", normalizeModelName(modelName));
        metadata.put("boundDocumentIds", documentIds);
        metadata.put("boundDocumentTags", documentTags);
        metadata.put("availableTools", tools);
        metadata.put("webSearchResultLimit", webSearchResultLimit);
        metadata.put("mandatoryToolCall", requireToolBeforeFinal);
        metadata.put("mandatoryTools", mandatoryTools);
        metadata.put("workflowMandatoryTools", workflowMandatoryTools);
        if (!workflowMandatoryResolution.skippedTools().isEmpty()) {
            metadata.put("workflowSkippedTools", workflowMandatoryResolution.skippedTools());
        }
        if (!workflowMandatoryResolution.skippedDecisions().isEmpty()) {
            metadata.put("workflowSkipDecisions", workflowDecisionEngine.decisionRecords(workflowMandatoryResolution.skippedDecisions()));
        }
        metadata.put("workflowDecisionEngine", true);
        metadata.put("requiredToolNames", normalizeList(requiredToolNames));
        metadata.put("documentWebVerificationRequired", requireDocumentWebVerification);
        metadata.put("documentSearchTool", documentSearchTool);
        metadata.put("verificationWebSearchTool", verificationWebSearchTool);
        metadata.put("maxSteps", maxSteps);
        if (maxToolCalls != Integer.MAX_VALUE) {
            metadata.put("maxToolCalls", maxToolCalls);
        }
        Object timeoutMs = requestRuntimeAttributes.get(AGENT_TIMEOUT_MS_ATTRIBUTE);
        if (timeoutMs != null) {
            metadata.put("timeoutMs", runtimeGuard.runtimeLong(timeoutMs, 0L));
        }
        metadata.put("plannerSteps", plannerSteps);

        log.info("[{}] Agent orchestration started. tools={}", requestId, tools.size());
        recordLifecyclePhase(
            requestRuntimeAttributes,
            metadata,
            "problem_identification",
            "Problem identified from user input.",
            metadataOf(
                "queryPreview", preview(query),
                "skillId", metadata.get("skillId"),
                "documentWebVerificationRequired", requireDocumentWebVerification
            )
        );
        recordLifecyclePhase(
            requestRuntimeAttributes,
            metadata,
            "tool_discovery",
            "Tool discovery completed and capability space was constructed.",
            metadataOf(
                "availableTools", tools,
                "mandatoryTools", mandatoryTools,
                "documentSearchTool", documentSearchTool,
                "verificationWebSearchTool", verificationWebSearchTool
            )
        );

        Set<String> completedWorkflowTools = new LinkedHashSet<>();
        completedWorkflowTools.addAll(workflowMandatoryResolution.skippedTools());
        ToolCallExecution pendingConfirmedExecution = executePendingConfirmedTool(
            query,
            conversationId,
            requestId,
            userId,
            tenantId,
            tools,
            workflowStateTracker.attributesWithCompletedTools(runtimeAttributes, completedWorkflowTools)
        );
        if (pendingConfirmedExecution != null) {
            traces.add(pendingConfirmedExecution.trace());
            observations.add("Confirmed pending " + pendingConfirmedExecution.observation());
            workflowStateTracker.rememberCompletedWorkflowTool(completedWorkflowTools, pendingConfirmedExecution);
            metadata.put("resumedPendingToolExecution", true);
            metadata.put("resumedPendingTool", pendingConfirmedExecution.trace() == null
                ? null
                : pendingConfirmedExecution.trace().getToolName());
            if (workflowStateTracker.isConfirmationRequired(pendingConfirmedExecution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return answerFinalizer.finishExecution("", traces, metadata, observations);
            }
        }

        if (!workflowMandatoryTools.isEmpty()) {
            metadata.put("runtimeEnforcedMcpWorkflow", true);
            metadata.put("mandatoryWorkflowPending", true);
        }

        for (int step = 1; step <= maxSteps; step++) {
            runtimeGuard.checkCancelled(cancellationCheck);
            long plannedAt = System.currentTimeMillis();
            Set<String> plannerCompletedTools = completedWorkflowToolsFromEvents(
                requestRuntimeAttributes,
                completedWorkflowToolsWithTraces(completedWorkflowTools, traces)
            );
            List<String> plannerMandatoryTools = workflowTools.missingMandatoryTools(mandatoryTools, plannerCompletedTools);
            boolean plannerRequiresToolBeforeFinal = !plannerMandatoryTools.isEmpty();
            AgentDecision decision = planner.decideNextAction(
                activeChatModel,
                query,
                systemPrompt,
                tools,
                observations,
                documentIds,
                documentTags,
                plannerMandatoryTools,
                plannerRequiresToolBeforeFinal,
                requireDocumentWebVerification,
                documentSearchTool,
                verificationWebSearchTool,
                requestRuntimeAttributes
            );
            runtimeGuard.checkCancelled(cancellationCheck);
            String plannedToolName = toolNames.normalizeToolName(decision.toolName(), tools);
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
            runResultAdapter.recordRuntimeStep(requestRuntimeAttributes, AGENT_RUN_ID_ATTRIBUTE, plannerStep);
            recordLifecyclePhase(
                requestRuntimeAttributes,
                metadata,
                "plan_generation",
                planGenerationLifecycleContent(decision),
                metadataOf(
                    "step", step,
                    "action", decision.action(),
                    "toolName", stringValue(decision.toolName()),
                    "resolvedToolName", plannedToolName,
                    "plannerProtocol", decision.executionPlan() == null ? null : decision.executionPlan().get("plannerProtocol")
                )
            );

            if (decision.interpretationPlan() != null
                && Boolean.TRUE.equals(decision.executionPlan().get("interpretationPlanValid"))) {
                return executeInterpretationPlanPipeline(
                    decision.interpretationPlan(),
                    activeChatModel,
                    query,
                    systemPrompt,
                    tenantId,
                    requestId,
                    conversationId,
                    userId,
                    tools,
                    workflowStateTracker.attributesWithCompletedTools(requestRuntimeAttributes, completedWorkflowTools),
                    traces,
                    observations,
                    metadata,
                    cancellationCheck
                );
            }

            if (FINAL.equals(decision.action())) {
                runtimeGuard.checkCancelled(cancellationCheck);
                Set<String> eventCompletedTools = completedWorkflowToolsFromEvents(
                    requestRuntimeAttributes,
                    completedWorkflowToolsWithTraces(completedWorkflowTools, traces)
                );
                List<String> eventMissingMandatoryTools = workflowTools.missingMandatoryTools(mandatoryTools, eventCompletedTools);
                FinalExecutionDecision finalDecision = eventMissingMandatoryTools.isEmpty()
                    ? new FinalExecutionDecision(true, "REQUIRED_TOOLS_COMPLETED_BY_EVENTS", eventMissingMandatoryTools)
                    : new FinalExecutionDecision(
                        Boolean.TRUE.equals(decision.sufficient()) || workflowDecisionEngine.policyAllowsEarlyFinal(requestRuntimeAttributes),
                        Boolean.TRUE.equals(decision.sufficient())
                            ? "PLANNER_SUFFICIENT"
                            : workflowDecisionEngine.policyAllowsEarlyFinal(requestRuntimeAttributes)
                                ? "POLICY_EARLY_EXIT"
                                : "MISSING_REQUIRED_TOOLS_BY_EVENTS",
                        eventMissingMandatoryTools
                    );
                metadata.put("finalDecisionReason", finalDecision.reason());
                metadata.put("plannerSufficient", Boolean.TRUE.equals(decision.sufficient()));
                metadata.put("policyAllowsEarlyFinal", workflowDecisionEngine.policyAllowsEarlyFinal(requestRuntimeAttributes));
                if (requireToolBeforeFinal && !finalDecision.allowed()) {
                    observations.add("Planner final answer rejected: this MCP-bound agent must observe all mandatory workflow tools before final answer. Missing: "
                        + finalDecision.missingMandatoryTools());
                    metadata.put("rejectedFinalBeforeTool", true);
                    metadata.put("missingMandatoryTools", finalDecision.missingMandatoryTools());
                    continue;
                }
                if (requireDocumentWebVerification
                    && workflowTools.missingDocumentWebVerification(eventCompletedTools, documentSearchTool, verificationWebSearchTool)) {
                    observations.add("Planner final answer rejected: document-web verification requires both document_search and "
                        + verificationWebSearchTool + " observations before final answer.");
                    metadata.put("rejectedFinalBeforeVerification", true);
                    continue;
                }
                return answerFinalizer.finishReviewedAnswer(
                    activeChatModel,
                    query,
                    systemPrompt,
                    traces,
                    metadata,
                    observations,
                    decision.answer(),
                    cancellationCheck,
                    "final_answer"
                );
            }

            if (!TOOL.equals(decision.action())) {
                observations.add("Planner returned unsupported action, fallback to final answer.");
                break;
            }

            if (plannedToolName == null || plannedToolName.isBlank()) {
                observations.add("Planner requested tool action without toolName.");
                break;
            }
            ToolExecutionDecision toolDecision = workflowDecisionEngine.resolveToolExecution(
                plannedToolName,
                false,
                null,
                Map.of(),
                tools,
                traces
            );
            if (toolDecision.outcome() == ToolExecutionOutcome.SKIP_POLICY) {
                observations.add("Planner requested unavailable tool: " + decision.toolName());
                workflowDecisionEngine.recordWorkflowDecision(metadata, toolDecision);
                continue;
            }
            if (toolDecision.outcome() == ToolExecutionOutcome.SKIP_DUPLICATE) {
                observations.add("Planner requested already completed tool " + plannedToolName
                    + "; runtime skipped the redundant tool call.");
                metadata.put("skippedRedundantTool", plannedToolName);
                workflowDecisionEngine.recordWorkflowDecision(metadata, toolDecision);
                continue;
            }
            Set<String> eventCompletedTools = completedWorkflowToolsFromEvents(
                requestRuntimeAttributes,
                completedWorkflowToolsWithTraces(completedWorkflowTools, traces)
            );
            String nextMandatoryTool = workflowTools.nextMandatoryTool(mandatoryTools, eventCompletedTools);
            boolean plannerFollowedWorkflow = nextMandatoryTool == null || toolNames.sameToolName(nextMandatoryTool, plannedToolName);
            if (requireToolBeforeFinal && !plannerFollowedWorkflow) {
                observations.add("Planner requested " + plannedToolName
                    + " but MCP workflow requires " + nextMandatoryTool + " next. Runtime will follow the Agent tool orchestration.");
                workflowTools.recordWorkflowOverride(metadata, plannedToolName, nextMandatoryTool, decision.reason());
                plannedToolName = nextMandatoryTool;
            }
            if (requireDocumentWebVerification
                && !eventCompletedTools.stream().anyMatch(tool -> toolNames.sameToolName(documentSearchTool, tool))
                && !toolNames.sameToolName(documentSearchTool, plannedToolName)) {
                observations.add("Planner requested " + plannedToolName
                    + " before " + documentSearchTool + "; document-web verification must start with " + documentSearchTool + ".");
                continue;
            }

            Map<String, Object> arguments = toolArguments.applyToolDefaults(
                plannedToolName,
                plannerFollowedWorkflow ? decision.arguments() : toolArguments.defaultToolArguments(plannedToolName, query, webSearchResultLimit),
                documentIds,
                documentTags,
                query,
                webSearchResultLimit
            );
            if (answerFinalizer.markToolBudgetExceeded(plannedToolName, maxToolCalls, traces, metadata, observations)) {
                return answerFinalizer.finishBudgetedSummary(activeChatModel, query, systemPrompt, traces, metadata, observations, cancellationCheck);
            }
            runtimeGuard.checkCancelled(cancellationCheck);
            ToolCallExecution execution = executeToolCall(
                plannedToolName,
                arguments,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                decision.executionPlan(),
                attributesWithWorkflowStep(
                    workflowStateTracker.attributesWithCompletedTools(requestRuntimeAttributes, completedWorkflowTools),
                    step,
                    plannedToolName
                )
            );
            traces.add(execution.trace());
            observations.add(execution.observation());
            if (workflowStateTracker.isConfirmationRequired(execution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return answerFinalizer.finishExecution("", traces, metadata, observations);
            }
            workflowStateTracker.rememberCompletedWorkflowTool(completedWorkflowTools, execution);
            runtimeGuard.checkCancelled(cancellationCheck);
        }

        if (requireToolBeforeFinal && traces.isEmpty()) {
            runtimeGuard.checkCancelled(cancellationCheck);
            String fallbackTool = mandatoryTools.get(0);
            Map<String, Object> fallbackArguments = toolArguments.applyDocumentSearchDefaults(
                fallbackTool,
                toolArguments.defaultToolArguments(fallbackTool, query, webSearchResultLimit),
                documentIds,
                documentTags
            );
            if (answerFinalizer.markToolBudgetExceeded(fallbackTool, maxToolCalls, traces, metadata, observations)) {
                return answerFinalizer.finishBudgetedSummary(activeChatModel, query, systemPrompt, traces, metadata, observations, cancellationCheck);
            }
            ToolCallExecution execution = executeToolCall(
                fallbackTool,
                fallbackArguments,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                Map.of(),
                workflowStateTracker.attributesWithCompletedTools(requestRuntimeAttributes, completedWorkflowTools)
            );
            traces.add(execution.trace());
            observations.add("Mandatory fallback " + execution.observation());
            metadata.put("mandatoryToolFallback", fallbackTool);
            if (workflowStateTracker.isConfirmationRequired(execution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return answerFinalizer.finishExecution("", traces, metadata, observations);
            }
            workflowStateTracker.rememberCompletedWorkflowTool(completedWorkflowTools, execution);
        }
        if (requireToolBeforeFinal) {
            runtimeGuard.checkCancelled(cancellationCheck);
            runMissingMandatoryWorkflowTools(
                traces,
                observations,
                query,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                mandatoryTools,
                documentIds,
                documentTags,
                webSearchResultLimit,
                metadata,
                requestRuntimeAttributes,
                maxToolCalls
            );
            if (Boolean.TRUE.equals(metadata.get("confirmationRequired"))) {
                return answerFinalizer.finishExecution("", traces, metadata, observations);
            }
            if (Boolean.TRUE.equals(metadata.get("toolBudgetExceeded"))) {
                return answerFinalizer.finishBudgetedSummary(activeChatModel, query, systemPrompt, traces, metadata, observations, cancellationCheck);
            }
        }
        if (requireDocumentWebVerification) {
            runtimeGuard.checkCancelled(cancellationCheck);
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
                requestRuntimeAttributes,
                maxToolCalls
            );
            if (Boolean.TRUE.equals(metadata.get("confirmationRequired"))) {
                return answerFinalizer.finishExecution("", traces, metadata, observations);
            }
            if (Boolean.TRUE.equals(metadata.get("toolBudgetExceeded"))) {
                return answerFinalizer.finishBudgetedSummary(activeChatModel, query, systemPrompt, traces, metadata, observations, cancellationCheck);
            }
        }

        return answerFinalizer.finishReviewedSummary(
            activeChatModel,
            query,
            systemPrompt,
            traces,
            metadata,
            observations,
            cancellationCheck,
            "max_steps_or_fallback"
        );
    }

    private AgentExecutionResult executeInterpretationPlanPipeline(InterpretationPlan plan,
                                                                   ChatModel activeChatModel,
                                                                   String query,
                                                                   String systemPrompt,
                                                                   String tenantId,
                                                                   String requestId,
                                                                   String conversationId,
                                                                   String userId,
                                                                   List<String> tools,
                                                                   Map<String, Object> runtimeAttributes,
                                                                   List<InteractionToolTrace> traces,
                                                                   List<String> observations,
                                                                   Map<String, Object> metadata,
                                                                   BooleanSupplier cancellationCheck) {
        runtimeGuard.checkCancelled(cancellationCheck);
        metadata.put("interpretationPlanPipeline", true);
        metadata.put("interpretationPlanVersion", plan.version());
        saveInterpretationPlanSnapshot("initial", plan, tenantId, requestId, runtimeAttributes, metadata);
        recordLifecyclePhase(
            runtimeAttributes,
            metadata,
            "step_execution",
            "InterpretationPlan DAG execution started.",
            metadataOf(
                "stage", "initial",
                "planVersion", plan.version(),
                "stepCount", plan.steps() == null ? 0 : plan.steps().size()
            )
        );

        InterpretationPlanValidator validator = new InterpretationPlanValidator();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            validator,
            runStore,
            request -> reviewInterpretationPlanToolResult(activeChatModel, query, systemPrompt, cancellationCheck, request),
            request -> decideInterpretationPlanDagStep(activeChatModel, query, systemPrompt, cancellationCheck, request)
        );
        InterpretationPlanRuntime.ExecutionResult firstResult = runtime.execute(planExecutionRequest(
            plan,
            tenantId,
            requestId,
            conversationId,
            userId,
            tools,
            runtimeAttributes
        ));
        recordPlanRuntimeResult("initial", firstResult, traces, observations, metadata);
        saveInterpretationPlanSnapshot("initial_result", plan, tenantId, requestId, runtimeAttributes, metadata, firstResult);
        runtimeGuard.checkCancelled(cancellationCheck);

        if (firstResult.approvalRequired()) {
            metadata.put("stopReason", "confirmation_required");
            metadata.put("confirmationRequired", true);
            return answerFinalizer.finishExecution("", traces, metadata, observations);
        }
        if (firstResult.success()) {
            recordMandatoryWorkflowCompletion(traces, metadata, runtimeAttributes);
            String synthesizedAnswer = synthesizeInterpretationPlanAnswer(
                activeChatModel,
                query,
                systemPrompt,
                firstResult,
                runtimeAttributes,
                observations,
                metadata,
                cancellationCheck,
                "initial"
            );
            return answerFinalizer.finishReviewedAnswer(
                activeChatModel,
                query,
                systemPrompt,
                traces,
                metadata,
                observations,
                synthesizedAnswer,
                cancellationCheck,
                "interpretation_plan_completed"
            );
        }

        InterpretationPlanRewriter rewriter = new InterpretationPlanRewriter(activeChatModel, objectMapper, validator);
        InterpretationPlan currentPlan = plan;
        InterpretationPlanRuntime.ExecutionResult currentResult = firstResult;
        int maxRewriteTimes = maxRewriteTimes(plan);
        metadata.put("interpretationPlanMaxRewriteTimes", maxRewriteTimes);
        for (int rewriteCount = 1; rewriteCount <= maxRewriteTimes; rewriteCount++) {
            InterpretationPlan.Step failedStep = failedStep(currentPlan, currentResult);
            InterpretationPlanRewriter.RewriteResult rewrite = rewriter.rewrite(new InterpretationPlanRewriter.RewriteRequest(
                currentPlan,
                failedStep,
                currentResult.errorMessage(),
                observations,
                tools,
                toolRegistry
            ));
            metadata.put("interpretationPlanRewriteAttempted", true);
            metadata.put("interpretationPlanRewriteCount", rewriteCount);
            metadata.put("interpretationPlanRewriteValid", rewrite.valid());
            metadata.put("interpretationPlanRewriteExecutable", rewrite.executable());
            if (rewrite.errorMessage() != null && !rewrite.errorMessage().isBlank()) {
                metadata.put("interpretationPlanRewriteError", rewrite.errorMessage());
            }
            runtimeGuard.checkCancelled(cancellationCheck);

            if (!rewrite.valid() || rewrite.rewrittenPlan() == null) {
                observations.add("InterpretationPlan rewrite failed: " + firstNonBlank(rewrite.errorMessage(), "rewriter did not return a valid plan"));
                break;
            }

            currentPlan = rewrite.rewrittenPlan();
            saveInterpretationPlanSnapshot(
                rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                currentPlan,
                tenantId,
                requestId,
                runtimeAttributes,
                metadata
            );
            recordLifecyclePhase(
                runtimeAttributes,
                metadata,
                "step_execution",
                "Rewritten InterpretationPlan DAG execution started.",
                metadataOf(
                    "stage", rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                    "planVersion", currentPlan.version(),
                    "stepCount", currentPlan.steps() == null ? 0 : currentPlan.steps().size()
                )
            );
            currentResult = runtime.execute(planExecutionRequest(
                currentPlan,
                tenantId,
                requestId,
                conversationId,
                userId,
                tools,
                runtimeAttributes
            ));
            recordPlanRuntimeResult(rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount, currentResult, traces, observations, metadata);
            saveInterpretationPlanSnapshot(
                (rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount) + "_result",
                currentPlan,
                tenantId,
                requestId,
                runtimeAttributes,
                metadata,
                currentResult
            );
            runtimeGuard.checkCancelled(cancellationCheck);

            if (currentResult.approvalRequired()) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return answerFinalizer.finishExecution("", traces, metadata, observations);
            }
            if (currentResult.success()) {
                recordMandatoryWorkflowCompletion(traces, metadata, runtimeAttributes);
                String synthesizedAnswer = synthesizeInterpretationPlanAnswer(
                    activeChatModel,
                    query,
                    systemPrompt,
                    currentResult,
                    runtimeAttributes,
                    observations,
                    metadata,
                    cancellationCheck,
                    rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount
                );
                return answerFinalizer.finishReviewedAnswer(
                    activeChatModel,
                    query,
                    systemPrompt,
                    traces,
                    metadata,
                    observations,
                    synthesizedAnswer,
                    cancellationCheck,
                    "interpretation_plan_rewritten"
                );
            }
        }

        metadata.put("interpretationPlanRewriteBudgetExceeded", maxRewriteTimes <= 0
            || firstInteger(metadata.get("interpretationPlanRewriteCount"), 0) >= maxRewriteTimes);
        metadata.put("interpretationPlanFallbackMode", fallbackMode(plan));
        metadata.put("stopReason", "interpretation_plan_failed");
        observations.add("InterpretationPlan failed after rewrite budget. Fallback mode: " + fallbackMode(plan) + ".");
        return answerFinalizer.finishReviewedSummary(
            activeChatModel,
            query,
            systemPrompt,
            traces,
            metadata,
            observations,
            cancellationCheck,
            "interpretation_plan_failed"
        );
    }

    @SuppressWarnings("unchecked")
    private void recordMandatoryWorkflowCompletion(List<InteractionToolTrace> traces,
                                                   Map<String, Object> metadata,
                                                   Map<String, Object> runtimeAttributes) {
        if (metadata == null || !Boolean.TRUE.equals(metadata.get("runtimeEnforcedMcpWorkflow"))) {
            if (!Boolean.TRUE.equals(metadata == null ? null : metadata.get("mandatoryToolCall"))) {
                return;
            }
        }
        Object mandatoryToolsValue = metadata.get("mandatoryTools");
        if (!(mandatoryToolsValue instanceof List<?> rawMandatoryTools)) {
            return;
        }
        List<String> mandatoryTools = rawMandatoryTools.stream()
            .map(String::valueOf)
            .filter(tool -> tool != null && !tool.isBlank())
            .toList();
        Set<String> eventCompletedTools = completedWorkflowToolsFromEvents(
            runtimeAttributes,
            completedWorkflowToolsWithTraces(Set.of(), traces)
        );
        List<String> missingMandatoryTools = workflowTools.missingMandatoryTools(mandatoryTools, eventCompletedTools);
        metadata.put("missingMandatoryTools", missingMandatoryTools);
        metadata.put("mandatoryWorkflowCompleted", missingMandatoryTools.isEmpty());
        metadata.put("mandatoryWorkflowPending", !missingMandatoryTools.isEmpty());
    }

    private InterpretationPlanRuntime.ExecutionRequest planExecutionRequest(InterpretationPlan plan,
                                                                            String tenantId,
                                                                            String requestId,
                                                                            String conversationId,
                                                                            String userId,
                                                                            List<String> tools,
                                                                            Map<String, Object> runtimeAttributes) {
        return new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            tools,
            tenantId,
            requestId,
            conversationId,
            userId,
            runtimeAttributes == null ? Map.of() : runtimeAttributes
        );
    }

    private InterpretationPlanRuntime.DagDecision decideInterpretationPlanDagStep(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        BooleanSupplier cancellationCheck,
        InterpretationPlanRuntime.DagDecisionRequest request
    ) {
        runtimeGuard.checkCancelled(cancellationCheck);
        if (activeChatModel == null || request == null) {
            return InterpretationPlanRuntime.DagDecision.abort("LLM DAG controller is unavailable.");
        }
        String prompt = buildInterpretationPlanDagDecisionPrompt(query, systemPrompt, request);
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=interpretation_plan_dag_decision decisionCount={} promptChars={} remainingStepCount={} completedStepCount={} modelClass={}",
            request.decisionCount(),
            prompt.length(),
            request.remainingStepIds() == null ? 0 : request.remainingStepIds().size(),
            request.completedStepIds() == null ? 0 : request.completedStepIds().size(),
            activeChatModel.getClass().getName());
        String raw = activeChatModel.chat(prompt);
        log.info("agentModelResponse phase=interpretation_plan_dag_decision decisionCount={} durationMs={} responseChars={}",
            request.decisionCount(),
            System.currentTimeMillis() - startedAt,
            raw == null ? 0 : raw.length());
        log.info("agentModelRawOutput phase=interpretation_plan_dag_decision decisionCount={} raw=\n{}",
            request.decisionCount(),
            raw == null ? "" : raw);
        Map<String, Object> payload = parseJsonObject(raw);
        if (payload.isEmpty()) {
            return new InterpretationPlanRuntime.DagDecision(
                InterpretationExecutionProtocol.VERSION,
                "abort",
                List.of(),
                "DAG controller did not return valid JSON.",
                null,
                metadataOf("raw", preview(raw))
            );
        }
        String protocolVersion = firstNonBlank(
            stringValue(firstObject(payload, "protocol_version", "protocolVersion")),
            InterpretationExecutionProtocol.VERSION
        );
        String action = firstNonBlank(
            stringValue(firstObject(payload, "action", "decision")),
            "abort"
        );
        List<Integer> stepIds = integerList(firstObject(payload, "step_ids", "stepIds", "steps"));
        Object singleStep = firstObject(payload, "step_id", "stepId");
        if (stepIds.isEmpty() && singleStep != null) {
            stepIds = integerList(List.of(singleStep));
        }
        String reason = firstNonBlank(
            stringValue(firstObject(payload, "reason", "analysis", "rationale")),
            "LLM DAG controller decision."
        );
        String finalAnswer = stringValue(firstObject(payload, "final_answer", "finalAnswer", "answer"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("raw", preview(raw));
        metadata.put("controllerPhase", "llm_decision");
        Object confidence = firstObject(payload, "confidence", "score");
        if (confidence != null) {
            metadata.put("confidence", confidence);
        }
        return new InterpretationPlanRuntime.DagDecision(protocolVersion, action, stepIds, reason, finalAnswer, metadata);
    }

    private String buildInterpretationPlanDagDecisionPrompt(String query,
                                                            String systemPrompt,
                                                            InterpretationPlanRuntime.DagDecisionRequest request) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are the responsible Agent Runtime DAG execution controller.\n");
        prompt.append("You, not Java code, decide which DAG node should run next.\n");
        prompt.append("Decision protocol:\n")
            .append(InterpretationExecutionProtocol.DECISION_SCHEMA)
            .append("\n");
        prompt.append("Runtime guard result contract for your decision:\n")
            .append(InterpretationExecutionProtocol.GUARD_RESULT_SCHEMA)
            .append("\n");
        prompt.append("Observation contract used for replay/debug:\n")
            .append(InterpretationExecutionProtocol.OBSERVATION_SCHEMA)
            .append("\n");
        prompt.append("Rules:\n");
        prompt.append("- Select only step ids from remaining_step_ids.\n");
        prompt.append("- Do not select a step until all of its depends_on steps are in completed_step_ids.\n");
        prompt.append("- Use execute_parallel_steps only when execution_policy.allow_parallel is true and every selected step is independently ready.\n");
        prompt.append("- Select the final_answer step only after its dependencies are complete and evidence is sufficient.\n");
        prompt.append("- If a required dependency failed, request rewrite_plan or abort instead of forcing a dependent step.\n");
        prompt.append("- Do not call tools directly; Java will only execute the step ids you choose after safety validation.\n\n");
        prompt.append("User query:\n").append(query == null ? "" : query).append("\n\n");
        prompt.append("decision_count: ").append(request.decisionCount()).append("\n");
        prompt.append("remaining_step_ids: ").append(request.remainingStepIds() == null ? List.of() : request.remainingStepIds()).append("\n");
        prompt.append("completed_step_ids: ").append(request.completedStepIds() == null ? List.of() : request.completedStepIds()).append("\n");
        prompt.append("current_final_answer_hint: ").append(firstNonBlank(request.finalAnswer(), "")).append("\n\n");
        prompt.append("Full InterpretationPlan:\n")
            .append(stringify(request.plan()))
            .append("\n\n");
        prompt.append("Executed step records:\n");
        if (request.executions() == null || request.executions().isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            for (InterpretationPlanRuntime.StepExecution execution : request.executions()) {
                prompt.append("- step=").append(execution.stepId())
                    .append(", action=").append(execution.actionType())
                    .append(", tool=").append(firstNonBlank(execution.toolName(), ""))
                    .append(", success=").append(execution.success())
                    .append(", error=").append(firstNonBlank(execution.errorMessage(), ""))
                    .append("\n");
                prompt.append("  output: ")
                    .append(shortObservationText(stringify(execution.output()), 3000))
                    .append("\n");
                if (execution.metadata() != null && !execution.metadata().isEmpty()) {
                    prompt.append("  metadata: ")
                        .append(shortObservationText(stringify(execution.metadata()), 1200))
                        .append("\n");
                }
            }
        }
        prompt.append("\nReturn only the decision JSON.");
        return prompt.toString();
    }

    private String planGenerationLifecycleContent(AgentDecision decision) {
        if (decision == null || decision.interpretationPlan() == null) {
            return "Planner generated the next action.";
        }
        Object valid = decision.executionPlan() == null ? null : decision.executionPlan().get("interpretationPlanValid");
        if (Boolean.TRUE.equals(valid)) {
            return "Planner generated an executable InterpretationPlan DAG.";
        }
        return "Planner generated an InterpretationPlan DAG candidate that failed runtime validation.";
    }

    private String synthesizeInterpretationPlanAnswer(ChatModel activeChatModel,
                                                      String query,
                                                      String systemPrompt,
                                                      InterpretationPlanRuntime.ExecutionResult result,
                                                      Map<String, Object> runtimeAttributes,
                                                      List<String> observations,
                                                      Map<String, Object> metadata,
                                                      BooleanSupplier cancellationCheck,
                                                      String stage) {
        runtimeGuard.checkCancelled(cancellationCheck);
        if (activeChatModel == null) {
            return result == null ? "" : firstNonBlank(result.finalAnswer(), "");
        }
        List<AgentObservation> storedObservations = storedInterpretationPlanObservations(runtimeAttributes);
        recordLifecyclePhase(
            runtimeAttributes,
            metadata,
            "final_synthesis",
            "Final synthesis started from executed steps and stored observations.",
            metadataOf(
                "stage", stage,
                "stepCount", result == null || result.steps() == null ? 0 : result.steps().size(),
                "storedObservationCount", storedObservations.size()
            )
        );
        String prompt = buildInterpretationPlanSummaryPrompt(query, systemPrompt, result, observations, storedObservations);
        String runId = stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE));
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=interpretation_plan_summary runId={} stage={} modelClass={} promptChars={} stepCount={} storedObservationCount={}",
            firstNonBlank(runId, ""),
            stage,
            activeChatModel.getClass().getName(),
            prompt.length(),
            result == null || result.steps() == null ? 0 : result.steps().size(),
            storedObservations.size());
        String answer = activeChatModel.chat(prompt);
        log.info("agentModelResponse phase=interpretation_plan_summary runId={} stage={} durationMs={} responseChars={}",
            firstNonBlank(runId, ""),
            stage,
            System.currentTimeMillis() - startedAt,
            answer == null ? 0 : answer.length());
        log.info("agentModelOutput phase=interpretation_plan_summary runId={} stage={} answer=\n{}",
            firstNonBlank(runId, ""),
            stage,
            answer == null ? "" : answer);
        if (metadata != null) {
            metadata.put("interpretationPlanSummaryGenerated", true);
            metadata.put("interpretationPlanSummaryStage", stage);
            metadata.put("interpretationPlanStoredObservationCount", storedObservations.size());
        }
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            "InterpretationPlan " + stage + " final stepwise summary generated.",
            "interpretation_plan_summary",
            metadataOf(
                "type", "final_summary",
                "workflow", "interpretation_plan",
                "stage", stage,
                "answerPreview", preview(answer)
            )
        );
        return answer == null || answer.isBlank()
            ? (result == null ? "" : firstNonBlank(result.finalAnswer(), ""))
            : answer;
    }

    private String buildInterpretationPlanSummaryPrompt(String query,
                                                        String systemPrompt,
                                                        InterpretationPlanRuntime.ExecutionResult result,
                                                        List<String> observations,
                                                        List<AgentObservation> storedObservations) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are the final step-by-step answer synthesizer for a completed MCP InterpretationPlan.\n");
        prompt.append("Answer the user in Chinese using only the executed step records, model review decisions, and stored observations.\n");
        prompt.append("Workflow contract:\n");
        prompt.append("- Treat every tool step as evidence only if it succeeded and the model review marked it satisfied.\n");
        prompt.append("- Use each step's review reason as the premise for later steps.\n");
        prompt.append("- Summarize what was done step by step, then provide the final answer.\n");
        prompt.append("- If some step failed or was rejected, state the limitation and do not use that result as evidence.\n");
        prompt.append("- Do not invent facts that are not present in the step outputs or observations.\n\n");
        prompt.append("User query:\n").append(query == null ? "" : query).append("\n\n");
        if (result != null && result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            prompt.append("Plan final answer hint, not authoritative evidence:\n")
                .append(result.finalAnswer())
                .append("\n\n");
        }
        prompt.append("Executed steps:\n");
        if (result == null || result.steps() == null || result.steps().isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
                prompt.append("- step=").append(step.stepId())
                    .append(", action=").append(step.actionType())
                    .append(", tool=").append(firstNonBlank(step.toolName(), ""))
                    .append(", success=").append(step.success())
                    .append(", durationMs=").append(step.durationMs())
                    .append("\n");
                if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
                    prompt.append("  error: ").append(step.errorMessage()).append("\n");
                }
                Map<String, Object> stepMetadata = step.metadata() == null ? Map.of() : step.metadata();
                if (!stepMetadata.isEmpty()) {
                    prompt.append("  review: satisfied=")
                        .append(stepMetadata.get("toolResultReviewSatisfied"))
                        .append(", reason=")
                        .append(stepMetadata.get("toolResultReviewReason"))
                        .append("\n");
                }
                prompt.append("  output: ")
                    .append(shortObservationText(stringify(step.output()), 4000))
                    .append("\n");
            }
        }
        prompt.append("\nStored RunStore/RocksDB observations:\n");
        if (storedObservations == null || storedObservations.isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            for (AgentObservation observation : storedObservations) {
                prompt.append("- type=").append(observation.type())
                    .append(", source=").append(observation.source())
                    .append(", content=").append(shortObservationText(observation.content(), 1000))
                    .append("\n");
                if (observation.metadata() != null && !observation.metadata().isEmpty()) {
                    prompt.append("  metadata: ")
                        .append(shortObservationText(stringify(observation.metadata()), 1600))
                        .append("\n");
                }
            }
        }
        if (observations != null && !observations.isEmpty()) {
            prompt.append("\nIn-memory observations:\n");
            observations.forEach(observation -> prompt.append("- ")
                .append(shortObservationText(observation, 1000))
                .append("\n"));
        }
        prompt.append("\nReturn only the final user-facing answer, no JSON.");
        return prompt.toString();
    }

    private List<AgentObservation> storedInterpretationPlanObservations(Map<String, Object> runtimeAttributes) {
        String runId = runtimeAttributes == null ? null : stringValue(runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE));
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return runStore.observations(runId).stream()
            .filter(observation -> observation != null && observation.metadata() != null)
            .filter(observation -> "interpretation_plan".equals(observation.metadata().get("workflow"))
                || "interpretation_plan_summary".equals(observation.source()))
            .toList();
    }

    private InterpretationPlanRuntime.StepReview reviewInterpretationPlanToolResult(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        BooleanSupplier cancellationCheck,
        InterpretationPlanRuntime.StepReviewRequest request
    ) {
        runtimeGuard.checkCancelled(cancellationCheck);
        if (activeChatModel == null || request == null || request.execution() == null) {
            return InterpretationPlanRuntime.StepReview.accepted("Model reviewer unavailable; accepting tool result.", Map.of(
                "toolResultReviewSkipped", true
            ));
        }
        long startedAt = System.currentTimeMillis();
        String runId = null;
        log.info("agentModelRequest phase=tool_result_review runId={} stepId={} tool={} attempt={}/{} modelClass={}",
            firstNonBlank(runId, ""),
            request.step() == null ? null : request.step().id(),
            request.execution().toolName(),
            request.attempt(),
            request.maxAttempts(),
            activeChatModel.getClass().getName());
        String raw = activeChatModel.chat(buildToolResultReviewPrompt(query, systemPrompt, request));
        log.info("agentModelResponse phase=tool_result_review runId={} stepId={} tool={} attempt={}/{} durationMs={} responseChars={}",
            firstNonBlank(runId, ""),
            request.step() == null ? null : request.step().id(),
            request.execution().toolName(),
            request.attempt(),
            request.maxAttempts(),
            System.currentTimeMillis() - startedAt,
            raw == null ? 0 : raw.length());
        log.info("agentModelRawOutput phase=tool_result_review runId={} stepId={} tool={} attempt={}/{} raw=\n{}",
            firstNonBlank(runId, ""),
            request.step() == null ? null : request.step().id(),
            request.execution().toolName(),
            request.attempt(),
            request.maxAttempts(),
            raw == null ? "" : raw);
        Map<String, Object> payload = parseJsonObject(raw);
        if (payload.isEmpty()) {
            return InterpretationPlanRuntime.StepReview.rejected(
                "Tool result review did not return valid JSON.",
                metadataOf("toolResultReviewRaw", preview(raw))
            );
        }
        boolean satisfied = booleanValue(firstObject(payload, "satisfied", "accepted", "sufficient"));
        String reason = firstNonBlank(
            stringValue(firstObject(payload, "reason", "feedback", "analysis")),
            satisfied ? "Tool result satisfies the plan step." : "Tool result does not satisfy the plan step."
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolResultReviewRaw", preview(raw));
        List<String> selectedUrls = stringList(firstObject(payload, "selected_urls", "selectedUrls", "urls"));
        if (!selectedUrls.isEmpty()) {
            metadata.put("selectedUrls", selectedUrls);
        }
        Object confidence = firstObject(payload, "confidence", "score");
        if (confidence != null) {
            metadata.put("toolResultReviewConfidence", confidence);
        }
        if (!satisfied && !selectedUrls.isEmpty() && isWebDiscoveryTool(request.execution().toolName())) {
            satisfied = true;
            metadata.put("toolResultReviewAutoAccepted", true);
            metadata.put("toolResultReviewAutoAcceptReason", "web discovery tool selected follow-up URLs");
            reason = "Discovery step selected follow-up URLs; continue to crawler/content step. Reviewer note: " + reason;
            log.info("Tool result review auto-accepted web discovery step tool={} stepId={} selectedUrls={}",
                request.execution().toolName(),
                request.step() == null ? null : request.step().id(),
                selectedUrls);
        }
        if (!satisfied
            && isDocumentSearchTool(request.execution().toolName())
            && hasUsableDocumentEvidence(request.execution().output())) {
            satisfied = true;
            metadata.put("toolResultReviewAutoAccepted", true);
            metadata.put("toolResultReviewAutoAcceptReason", "document search returned usable partial evidence");
            reason = "Document search returned usable partial evidence for synthesis; continue to dependent steps. Reviewer note: " + reason;
            log.info("Tool result review auto-accepted document evidence step tool={} stepId={}",
                request.execution().toolName(),
                request.step() == null ? null : request.step().id());
        }
        return satisfied
            ? InterpretationPlanRuntime.StepReview.accepted(reason, metadata)
            : InterpretationPlanRuntime.StepReview.rejected(reason, metadata);
    }

    private String buildToolResultReviewPrompt(String query,
                                               String systemPrompt,
                                               InterpretationPlanRuntime.StepReviewRequest request) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are the runtime reviewer for one completed MCP tool call.\n");
        prompt.append("Return strict JSON only with this shape:\n");
        prompt.append("{\"satisfied\":true|false,\"reason\":\"short reason\",\"selected_urls\":[\"https://...\"],\"confidence\":0.0}\n");
        prompt.append("Rules:\n");
        prompt.append("- Decide whether this tool output is sufficient for the current plan step and user request.\n");
        prompt.append("- If satisfied=false, the DAG must not continue to dependent steps.\n");
        prompt.append("- For web discovery tools (web_search, web_page_analyze, site_intelligence_resolver, *_site_search), judge candidate URLs/snippets only. Do not require full article content from these tools.\n");
        prompt.append("- If a web discovery tool returns useful URLs for follow-up crawling or page analysis, set satisfied=true and put those URLs in selected_urls.\n");
        prompt.append("- For crawl/content tools, judge whether the fetched full content is relevant and usable for analysis.\n");
        prompt.append("- For document_search, judge whether the result contains relevant document evidence that can support later synthesis. Do not require one chunk to contain the complete final answer or every requested example.\n");
        prompt.append("- Accept document_search when multiple chunks collectively mention relevant entities, APIs, tables, citations, or snippets, even if the final answer must combine them and state missing pieces.\n");
        prompt.append("- Reject document_search only when it failed, returned no useful results, violated an explicit source constraint, or is unrelated to the request.\n");
        prompt.append("- If the user required an official source, reject results that do not satisfy that source constraint.\n");
        prompt.append("- Do not answer the user here; only review the tool result.\n\n");
        prompt.append("Attempt: ").append(request.attempt()).append('/').append(request.maxAttempts()).append("\n");
        prompt.append("User query:\n").append(query == null ? "" : query).append("\n\n");
        InterpretationPlan plan = request.plan();
        prompt.append("Plan intent:\n")
            .append(plan == null || plan.intent() == null ? "" : stringify(plan.intent()))
            .append("\n\n");
        prompt.append("Current step:\n")
            .append(request.step() == null ? "" : stringify(request.step()))
            .append("\n\n");
        prompt.append("Tool output:\n")
            .append(shortObservationText(stringify(request.execution().output()), 9000));
        return prompt.toString();
    }

    private boolean isWebDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("web_search")
            || semantic.endsWith("_web_search")
            || semantic.contains("web_search")
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

    private boolean isDocumentSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("document_search")
            || semantic.endsWith("_document_search")
            || (semantic.contains("document") && semantic.contains("search"));
    }

    private boolean hasUsableDocumentEvidence(Object output) {
        if (output == null) {
            return false;
        }
        Map<String, Object> root = asMap(output);
        if (!root.isEmpty()) {
            if (hasUsableDocumentEvidenceMap(root)) {
                return true;
            }
            Object nested = firstObject(root, "output", "data", "result", "body");
            if (nested != null && nested != output) {
                return hasUsableDocumentEvidence(nested);
            }
        }
        if (!(output instanceof String text) || text.isBlank()) {
            return false;
        }
        Map<String, Object> parsed = parseJsonObject(text);
        if (!parsed.isEmpty()) {
            return hasUsableDocumentEvidence(parsed);
        }
        String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compact.contains("\"total\":0") || compact.contains("\"count\":0")) {
            return false;
        }
        return compact.contains("document_evidence_v1")
            && (compact.contains("\"results\":[{")
                || compact.contains("\"citations\":[{")
                || compact.contains("\"evidencesnippets\":[{")
                || compact.contains("doc://")
                || compact.contains("documentevidencesnippets"));
    }

    private boolean hasUsableDocumentEvidenceMap(Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            return false;
        }
        if (candidateListPresent(root, "results")
            || candidateListPresent(root, "items")
            || candidateListPresent(root, "records")
            || candidateListPresent(root, "citations")
            || candidateListPresent(root, "evidenceSnippets")
            || candidateListPresent(root, "evidence_snippets")
            || candidateListPresent(root, "evidence_chunks")) {
            return true;
        }
        String contractVersion = stringValue(firstObject(root, "contractVersion", "contract_version"));
        if (contractVersion != null && "document_evidence_v1".equalsIgnoreCase(contractVersion)) {
            Integer total = firstInteger(
                firstObject(root, "total", "totalCount", "count", "returned", "resultCount"),
                0
            );
            return total > 0;
        }
        return false;
    }

    private boolean candidateListPresent(Map<String, Object> root, String key) {
        Object value = root == null ? null : root.get(key);
        return value instanceof List<?> list && !list.isEmpty();
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

    private int maxRewriteTimes(InterpretationPlan plan) {
        Integer configured = plan == null || plan.executionPolicy() == null
            ? null
            : plan.executionPolicy().maxRewriteTimes();
        return configured == null ? 1 : Math.max(0, configured);
    }

    private String fallbackMode(InterpretationPlan plan) {
        String configured = plan == null || plan.executionPolicy() == null
            ? null
            : plan.executionPolicy().fallbackMode();
        if ("partial_result".equals(configured) || "safe_answer".equals(configured)) {
            return configured;
        }
        return "safe_answer";
    }

    private void recordPlanRuntimeResult(String stage,
                                         InterpretationPlanRuntime.ExecutionResult result,
                                         List<InteractionToolTrace> traces,
                                         List<String> observations,
                                         Map<String, Object> metadata) {
        if (result == null) {
            return;
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("stage", stage);
            record.put("stepId", step.stepId());
            record.put("actionType", step.actionType());
            record.put("toolName", step.toolName());
            record.put("success", step.success());
            record.put("durationMs", step.durationMs());
            if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
                record.put("errorMessage", step.errorMessage());
            }
            if (step.metadata() != null && !step.metadata().isEmpty()) {
                record.put("metadata", step.metadata());
            }
            records.add(record);
            if (step.toolExecution() != null && step.toolExecution().trace() != null) {
                traces.add(step.toolExecution().trace());
            }
            observations.add(planStepObservation(stage, step));
            String canonicalEvidenceObservation = canonicalEvidenceObservation(step);
            if (canonicalEvidenceObservation != null && !canonicalEvidenceObservation.isBlank()) {
                observations.add(canonicalEvidenceObservation);
                record.put("canonicalEvidenceObservation", true);
            }
        }
        metadata.put("interpretationPlan" + capitalize(stage) + "Status", result.status());
        metadata.put("interpretationPlan" + capitalize(stage) + "Success", result.success());
        metadata.put("interpretationPlan" + capitalize(stage) + "DurationMs", result.durationMs());
        if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
            metadata.put("interpretationPlan" + capitalize(stage) + "Error", result.errorMessage());
        }
        addCandidateList(metadataList(metadata, "interpretationPlanStepExecutions"), records);
    }

    private void saveInterpretationPlanSnapshot(String stage,
                                                InterpretationPlan plan,
                                                String tenantId,
                                                String requestId,
                                                Map<String, Object> runtimeAttributes,
                                                Map<String, Object> metadata) {
        if (interpretationPlanStore == null || plan == null) {
            return;
        }
        String taskId = firstNonBlank(
            stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)),
            requestId
        );
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String normalizedStage = stage == null || stage.isBlank() ? "generated" : stage.trim();
        String planId = taskId + "-" + normalizedStage;
        try {
            InterpretationPlanRecord record = interpretationPlanStore.savePlan(
                firstNonBlank(tenantId, "default"),
                taskId,
                planId,
                plan,
                "GENERATED"
            );
            if (metadata != null) {
                metadata.put("interpretationPlanId", record.planId());
                metadata.put("interpretationPlanSnapshotVersion", record.version());
                metadata.put("interpretationPlanDagStored", true);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to save InterpretationPlan snapshot. taskId={} stage={} error={}",
                taskId, normalizedStage, ex.getMessage());
        }
    }

    private void saveInterpretationPlanSnapshot(String stage,
                                                InterpretationPlan plan,
                                                String tenantId,
                                                String requestId,
                                                Map<String, Object> runtimeAttributes,
                                                Map<String, Object> metadata,
                                                InterpretationPlanRuntime.ExecutionResult result) {
        if (interpretationPlanStore == null || plan == null || result == null) {
            return;
        }
        String taskId = firstNonBlank(
            stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)),
            requestId
        );
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String normalizedStage = stage == null || stage.isBlank() ? "execution_result" : stage.trim();
        String planId = taskId + "-" + normalizedStage;
        try {
            Map<String, Object> dag = interpretationPlanDagConverter.convert(plan, normalizedStage, result);
            InterpretationPlanRecord record = interpretationPlanStore.savePlan(
                firstNonBlank(tenantId, "default"),
                taskId,
                planId,
                plan,
                result.success() ? "COMPLETED" : "FAILED",
                dag
            );
            if (metadata != null) {
                metadata.put("interpretationPlanId", record.planId());
                metadata.put("interpretationPlanSnapshotVersion", record.version());
                metadata.put("interpretationPlanDagStored", true);
                metadata.put("interpretationPlanExecutionDagStored", true);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to save InterpretationPlan execution snapshot. taskId={} stage={} error={}",
                taskId, normalizedStage, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> metadataList(Map<String, Object> metadata, String key) {
        Object existing = metadata.get(key);
        if (existing instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> values = new ArrayList<>();
        metadata.put(key, values);
        return values;
    }

    private String planStepObservation(String stage, InterpretationPlanRuntime.StepExecution step) {
        if (step.success()) {
            if ("final_answer".equals(step.actionType())) {
                return "InterpretationPlan " + stage + " final answer step " + step.stepId() + " completed.";
            }
            return "InterpretationPlan " + stage + " step " + step.stepId() + " "
                + firstNonBlank(step.toolName(), step.actionType()) + " succeeded.";
        }
        return "InterpretationPlan " + stage + " step " + step.stepId() + " "
            + firstNonBlank(step.toolName(), step.actionType()) + " failed: "
            + firstNonBlank(step.errorMessage(), "unknown error");
    }

    private String canonicalEvidenceObservation(InterpretationPlanRuntime.StepExecution step) {
        if (step == null || !step.success() || step.toolExecution() == null || step.toolExecution().output() == null) {
            return null;
        }
        String observation = toolObservationBuilder.buildSuccessObservation(
            step.toolName(),
            step.toolExecution().output(),
            stringify(step.output())
        );
        return hasCanonicalEvidence(observation) ? observation : null;
    }

    private boolean hasCanonicalEvidence(String observation) {
        return observation != null
            && (observation.contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            || observation.contains("Evidence graph execution (contractVersion=evidence_graph_v1)")
            || observation.contains("Evidence OS execution (contractVersion=evidence_os_execution_v2)")
            || observation.contains("Unified evidence context (contractVersion=evidence_v1)")
            || observation.contains("doc://")
            || observation.contains("web://"));
    }

    private InterpretationPlan.Step failedStep(InterpretationPlan plan, InterpretationPlanRuntime.ExecutionResult result) {
        if (plan == null || result == null || result.steps() == null) {
            return null;
        }
        Integer failedStepId = result.steps().stream()
            .filter(step -> !step.success())
            .map(InterpretationPlanRuntime.StepExecution::stepId)
            .findFirst()
            .orElse(null);
        if (failedStepId == null) {
            return null;
        }
        return plan.steps().stream()
            .filter(step -> failedStepId.equals(step.id()))
            .findFirst()
            .orElse(null);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1);
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
        String pendingToolName = toolNames.normalizeToolName(stringValue(pending.get("toolName")), tools);
        if (pendingToolName == null || pendingToolName.isBlank() || !tools.contains(pendingToolName)) {
            return null;
        }
        Map<String, Object> arguments = asMap(pending.get("input"));
        Map<String, Object> executionPlan = asMap(pending.get("executionPlan"));
        return executeToolCall(
            pendingToolName,
            toolArguments.applyToolDefaults(pendingToolName, arguments, List.of(), List.of(), query, WEB_SEARCH_REFERENCE_LIMIT),
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
        ToolOutput output = execution.output();
        String outputText = stringify(output.getData());
        InteractionToolTrace trace = execution.trace();

        String observation = output.isSuccess()
            ? toolObservationBuilder.buildSuccessObservation(toolName, output, outputText)
            : toolObservationBuilder.buildFailureObservation(toolName, output);
        recordStructuredToolObservation(runtimeAttributes, toolName, output, execution, observation);
        return new ToolCallExecution(trace, observation);
    }

    private Map<String, Object> attributesWithWorkflowStep(Map<String, Object> runtimeAttributes,
                                                           Integer stepId,
                                                           String toolName) {
        Map<String, Object> attributes = new LinkedHashMap<>(runtimeAttributes == null ? Map.of() : runtimeAttributes);
        if (stepId != null) {
            attributes.put("workflowStepId", stepId);
        }
        if (toolName != null && !toolName.isBlank()) {
            attributes.put("workflowToolName", toolName);
        }
        return attributes;
    }

    private void recordStructuredToolObservation(Map<String, Object> runtimeAttributes,
                                                 String toolName,
                                                 ToolOutput output,
                                                 ToolRuntimeExecution execution,
                                                 String observation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", output != null && output.isSuccess() ? "tool" : "tool_failure");
        metadata.put("toolName", toolName);
        metadata.put("success", output != null && output.isSuccess());
        metadata.put("outcome", execution == null ? null : execution.outcome());
        copyAttribute(runtimeAttributes, metadata, "workflowStepId");
        copyAttribute(runtimeAttributes, metadata, "workflowToolName");
        copyAttribute(runtimeAttributes, metadata, "interpretationPlanStepId");
        copyAttribute(runtimeAttributes, metadata, "interpretationPlanActionType");
        if (output != null && output.getErrorMessage() != null && !output.getErrorMessage().isBlank()) {
            metadata.put("errorMessage", output.getErrorMessage());
        }
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            observation,
            toolName,
            metadata
        );
    }

    private void copyAttribute(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Set<String> completedWorkflowToolsFromEvents(Map<String, Object> runtimeAttributes,
                                                         Set<String> fallbackCompletedTools) {
        Set<String> completed = new LinkedHashSet<>(fallbackCompletedTools == null ? Set.of() : fallbackCompletedTools);
        String runId = stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE));
        if (runId == null || runId.isBlank()) {
            return completed;
        }
        completed.addAll(workflowStateTracker.completedToolsFromEvents(runStore.events(runId)));
        return completed;
    }

    private Set<String> completedWorkflowToolsWithTraces(Set<String> completedWorkflowTools,
                                                         List<InteractionToolTrace> traces) {
        Set<String> completed = new LinkedHashSet<>(completedWorkflowTools == null ? Set.of() : completedWorkflowTools);
        completed.addAll(workflowStateTracker.completedToolsFromTraces(traces));
        return completed;
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

    private void runMissingMandatoryWorkflowTools(List<InteractionToolTrace> traces,
                                                  List<String> observations,
                                                  String query,
                                                  String conversationId,
                                                  String requestId,
                                                  String userId,
                                                  String tenantId,
                                                  List<String> tools,
                                                  List<String> mandatoryTools,
                                                  List<String> documentIds,
                                                  List<String> documentTags,
                                                  int webSearchResultLimit,
                                                  Map<String, Object> metadata,
                                                  Map<String, Object> runtimeAttributes,
                                                  int maxToolCalls) {
        List<String> fallbackTools = new ArrayList<>();
        Set<String> completedTools = completedWorkflowToolsFromEvents(
            runtimeAttributes,
            workflowStateTracker.completedToolsFromTraces(traces)
        );
        String nextTool = workflowTools.nextMandatoryTool(mandatoryTools, completedTools);
        while (nextTool != null && !fallbackTools.contains(nextTool)) {
            fallbackTools.add(nextTool);
            nextTool = workflowTools.missingMandatoryTools(mandatoryTools, completedTools).stream()
                .filter(tool -> !fallbackTools.contains(tool))
                .findFirst()
                .orElse(null);
        }
        if (fallbackTools.isEmpty()) {
            return;
        }
        metadata.put("mandatoryWorkflowExecutionTools", fallbackTools);
        for (String fallbackTool : fallbackTools) {
            completedTools = completedWorkflowToolsFromEvents(
                runtimeAttributes,
                workflowStateTracker.completedToolsFromTraces(traces)
            );
            if (fallbackTool == null || !tools.contains(fallbackTool)
                || completedTools.stream().anyMatch(tool -> toolNames.sameToolName(fallbackTool, tool))) {
                continue;
            }
            if (answerFinalizer.markToolBudgetExceeded(fallbackTool, maxToolCalls, traces, metadata, observations)) {
                return;
            }
            Map<String, Object> fallbackArguments = toolArguments.applyToolDefaults(
                fallbackTool,
                toolArguments.defaultToolArguments(fallbackTool, query, webSearchResultLimit),
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
                workflowStateTracker.attributesWithCompletedTools(runtimeAttributes, completedTools)
            );
            traces.add(execution.trace());
            observations.add("Mandatory workflow execution " + execution.observation());
            if (workflowStateTracker.isConfirmationRequired(execution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return;
            }
            if (execution.trace() == null || !execution.trace().isSuccess()) {
                metadata.put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                return;
            }
            runtimeAttributes = workflowStateTracker.attributesWithCompletedTools(
                runtimeAttributes,
                completedWorkflowToolsFromEvents(runtimeAttributes, workflowStateTracker.completedToolsFromTraces(traces))
            );
        }
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
     * @param maxToolCalls the max tool calls value
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
                                                   Map<String, Object> runtimeAttributes,
                                                   int maxToolCalls) {
        List<String> fallbackTools = new ArrayList<>();
        Set<String> completedTools = completedWorkflowToolsFromEvents(
            runtimeAttributes,
            workflowStateTracker.completedToolsFromTraces(traces)
        );
        if (!completedTools.stream().anyMatch(tool -> toolNames.sameToolName(documentSearchTool, tool))) {
            fallbackTools.add(documentSearchTool);
        }
        if (!completedTools.stream().anyMatch(tool -> toolNames.sameToolName(verificationWebSearchTool, tool))) {
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
            if (answerFinalizer.markToolBudgetExceeded(fallbackTool, maxToolCalls, traces, metadata, observations)) {
                return;
            }
            Map<String, Object> fallbackArguments = toolArguments.applyToolDefaults(
                fallbackTool,
                toolArguments.defaultToolArguments(fallbackTool, query, webSearchResultLimit),
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
                workflowStateTracker.attributesWithCompletedTools(runtimeAttributes, completedTools)
            );
            traces.add(execution.trace());
            observations.add("Document-web verification fallback " + execution.observation());
            completedTools = completedWorkflowToolsFromEvents(runtimeAttributes, workflowStateTracker.completedToolsFromTraces(traces));
            runtimeAttributes = workflowStateTracker.attributesWithCompletedTools(runtimeAttributes, completedTools);
            if (workflowStateTracker.isConfirmationRequired(execution)) {
                metadata.put("stopReason", "confirmation_required");
                metadata.put("confirmationRequired", true);
                return;
            }
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(extractJsonObject(raw), Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String extractJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
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

    private void recordLifecyclePhase(Map<String, Object> runtimeAttributes,
                                      Map<String, Object> metadata,
                                      String phase,
                                      String content,
                                      Map<String, Object> phaseMetadata) {
        if (phase == null || phase.isBlank()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", "lifecycle");
        values.put("workflow", WORKFLOW_PROBLEM_SOLVING);
        values.put("lifecyclePhase", phase);
        values.put("createdAt", System.currentTimeMillis());
        values.putAll(phaseMetadata == null ? Map.of() : phaseMetadata);
        if (metadata != null) {
            metadataList(metadata, "agentLifecyclePhases").add(values);
        }
        log.info("agentLifecycle phase={} runId={} content={}",
            phase,
            firstNonBlank(stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)), ""),
            content);
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            content,
            "agent_lifecycle",
            values
        );
    }

    private Map<String, Object> metadataOf(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key == null) {
                continue;
            }
            Object value = keyValues[i + 1];
            if (value != null) {
                values.put(String.valueOf(key), value);
            }
        }
        return values;
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

    private List<Integer> integerList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::integerValue)
                .filter(item -> item != null)
                .distinct()
                .toList();
        }
        Integer single = integerValue(value);
        return single == null ? List.of() : List.of(single);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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

    record ToolCallExecution(
        InteractionToolTrace trace,
        String observation
    ) {
    }

    public record AgentExecutionResult(
        String answer,
        List<InteractionToolTrace> toolTraces,
        Map<String, Object> metadata
    ) {
    }
}
