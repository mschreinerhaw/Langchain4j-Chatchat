package com.chatchat.agents.orchestration;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentObservationPipeline;
import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRunStore;
import com.chatchat.agents.runtime.AgentRuntimeFactGroundingContract;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final int MAX_INTERPRETATION_PLAN_ATTEMPTS = 3;
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
    private final SqlMetadataAnswerRenderer sqlMetadataAnswerRenderer = new SqlMetadataAnswerRenderer();
    private final SqlMetadataGroundingGuard sqlMetadataGroundingGuard = new SqlMetadataGroundingGuard();
    private final ExecutionGraphSemanticValidator executionGraphSemanticValidator = new ExecutionGraphSemanticValidator();
    private final InterpretationPlanWorkflowGuard interpretationPlanWorkflowGuard = new InterpretationPlanWorkflowGuard();

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
        this.toolArguments = new AgentToolArgumentResolver(this.toolNames, WEB_SEARCH_REFERENCE_LIMIT, this.toolRegistry);
        this.workflowTools = new AgentWorkflowToolResolver(this.toolNames);
        this.answerFinalizer = new AgentAnswerFinalizer(resolvedAnswerReviewer, this.runtimeGuard, modelsConfig);
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
        attributes.put(AGENT_TIMEOUT_MS_ATTRIBUTE,
            request.getTimeoutMs() == null ? AgentRunRequest.DEFAULT_TIMEOUT_MS : request.getTimeoutMs());
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
        Map<String, Object> requestRuntimeAttributes = new LinkedHashMap<>(
            runtimeGuard.attributesWithDeadline(runtimeAttributes)
        );
        if (query != null && !query.isBlank()) {
            requestRuntimeAttributes.putIfAbsent("originalUserQuery", query);
        }
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
        List<Map<String, Object>> requiredToolExecutionContracts = requiredToolExecutionContracts(
            mandatoryTools,
            requiredToolNames,
            workflowMandatoryTools
        );
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
        metadata.put("executionPolicy", runtimeExecutionPolicy(requireToolBeforeFinal));
        metadata.put("factGroundingContract", AgentRuntimeFactGroundingContract.metadata());
        metadata.put("requiredToolExecutions", requiredToolExecutionContracts);
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
            String plannedToolName = toolNames.normalizeToolName(decision.toolName(), decision.arguments(), tools);
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
                Set<String> eventCompletedTools = completedWorkflowToolsFromEvents(
                    requestRuntimeAttributes,
                    completedWorkflowToolsWithTraces(completedWorkflowTools, traces)
                );
                String nextMandatoryTool = workflowTools.nextMandatoryTool(mandatoryTools, eventCompletedTools);
                if (requireToolBeforeFinal
                    && nextMandatoryTool != null
                    && !toolNames.sameToolName(nextMandatoryTool, plannedToolName)) {
                    observations.add("Planner produced an InterpretationPlan starting with " + plannedToolName
                        + " but MCP workflow requires " + nextMandatoryTool
                        + " next. Runtime will follow the Agent tool orchestration.");
                    workflowTools.recordWorkflowOverride(metadata, plannedToolName, nextMandatoryTool, decision.reason());
                } else {
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
                        documentIds,
                        documentTags,
                        webSearchResultLimit,
                        maxToolCalls,
                        cancellationCheck
                    );
                }
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
                        false,
                        "MISSING_REQUIRED_TOOLS_BY_EVENTS",
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
                traces,
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
            Map<String, Object> fallbackArguments = toolArguments.applyToolDefaults(
                fallbackTool,
                toolArguments.defaultToolArguments(fallbackTool, query, webSearchResultLimit),
                documentIds,
                documentTags,
                query,
                webSearchResultLimit
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
                traces,
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

        AgentExecutionResult blockedResult = finishMandatoryWorkflowBlockedIfPending(
            activeChatModel,
            query,
            systemPrompt,
            traces,
            metadata,
            observations,
            requestRuntimeAttributes,
            cancellationCheck,
            "mandatory_workflow_incomplete",
            "Agent run stopped before final answer because mandatory workflow tools did not complete."
        );
        if (blockedResult != null) {
            return blockedResult;
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
                                                                   List<String> documentIds,
                                                                   List<String> documentTags,
                                                                   int webSearchResultLimit,
                                                                   int maxToolCalls,
                                                                   BooleanSupplier cancellationCheck) {
        runtimeGuard.checkCancelled(cancellationCheck);
        metadata.put("interpretationPlanPipeline", true);
        metadata.put("interpretationPlanVersion", plan.version());
        saveInterpretationPlanSnapshot("initial", plan, tenantId, requestId, runtimeAttributes, metadata);
        recordPlanEvolution(null, plan, 1, "INITIAL", List.of(), runtimeAttributes, metadata);

        InterpretationPlanValidator validator = new InterpretationPlanValidator();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            validator,
            runStore,
            request -> reviewInterpretationPlanToolResult(activeChatModel, query, systemPrompt, cancellationCheck, request),
            request -> decideInterpretationPlanDagStep(activeChatModel, query, systemPrompt, cancellationCheck, request)
        );
        List<InterpretationPlanRuntime.ExecutionResult> planAttemptResults = new ArrayList<>();
        List<Map<String, Object>> evidenceHistory = new ArrayList<>();
        InterpretationPlanValidator.ValidationResult initialEvaluation = validator.validate(
            plan,
            toolRegistry,
            new LinkedHashSet<>(tools == null ? List.of() : tools)
        );
        recordInterpretationPlanEvaluation("initial", initialEvaluation, runtimeAttributes, metadata);
        InterpretationPlanRuntime.ExecutionResult firstResult;
        if (initialEvaluation.valid()) {
            recordInterpretationPlanExecutionStarted("initial", plan, runtimeAttributes, metadata);
            firstResult = runtime.execute(planExecutionRequest(
                plan,
                tenantId,
                requestId,
                conversationId,
                userId,
                tools,
                workflowAttemptAttributes(runtimeAttributes, 0)
            ));
        } else {
            firstResult = planEvaluationFailure("initial", initialEvaluation);
        }
        recordPlanRuntimeResult("initial", firstResult, traces, observations, metadata);
        saveInterpretationPlanSnapshot("initial_result", plan, tenantId, requestId, runtimeAttributes, metadata, firstResult);
        runtimeGuard.checkCancelled(cancellationCheck);

        if (firstResult.approvalRequired()) {
            metadata.put("stopReason", "confirmation_required");
            metadata.put("confirmationRequired", true);
            return answerFinalizer.finishExecution("", traces, metadata, observations);
        }
        InterpretationPlanWorkflowGuard.GuardResult firstWorkflowGuard = interpretationPlanWorkflowGuard.evaluate(
            plan,
            firstResult,
            metadataStringList(metadata, "mandatoryTools"),
            metadataStringList(runtimeAttributes, "workflowCompletedTools")
        );
        if (firstResult.success() && !firstWorkflowGuard.allowed()) {
            firstResult = blockIncompleteWorkflow("initial", firstResult, firstWorkflowGuard, observations, metadata);
        }
        firstResult = rejectUnsatisfiedInterpretationPlanResult("initial", firstResult, observations, metadata);
        planAttemptResults.add(firstResult);
        Map<String, Object> firstEvidence = analyzeInterpretationPlanEvidence(
            activeChatModel, query, systemPrompt, plan, firstResult, 1, evidenceHistory,
            runtimeAttributes, metadata, cancellationCheck
        );
        evidenceHistory.add(firstEvidence);
        if (firstResult.success() && evidenceSufficient(firstEvidence)) {
            recordEvidenceStopState(metadata, firstEvidence, "evidence_sufficient", 1);
            recordMandatoryWorkflowCompletion(traces, metadata, runtimeAttributes);
            String synthesizedAnswer = synthesizeInterpretationPlanAnswer(
                activeChatModel,
                query,
                systemPrompt,
                firstResult,
                planAttemptResults,
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
                "evidence_sufficient"
            );
        }

        InterpretationPlanRewriter rewriter = new InterpretationPlanRewriter(activeChatModel, objectMapper, validator);
        InterpretationPlan currentPlan = plan;
        InterpretationPlanRuntime.ExecutionResult currentResult = firstResult;
        int maxRewriteTimes = maxRewriteTimes(plan);
        boolean duplicateToolPlanSuppressed = false;
        metadata.put("interpretationPlanMaxRewriteTimes", maxRewriteTimes);
        for (int rewriteCount = 1; rewriteCount <= maxRewriteTimes; rewriteCount++) {
            observations.add(planAttemptRewriteSummary(
                rewriteCount,
                currentPlan,
                currentResult
            ));
            InterpretationPlan.Step failedStep = failedStep(currentPlan, currentResult);
            Set<String> completedTools = completedWorkflowToolsFromEvents(
                runtimeAttributes,
                workflowStateTracker.completedToolsFromTraces(traces)
            );
            List<String> pendingRequiredTools = workflowTools.missingMandatoryTools(
                metadataStringList(metadata, "mandatoryTools"),
                completedTools
            );
            List<InterpretationPlanRewriter.RequiredToolExecution> rewriteRequirements = new ArrayList<>(
                requiredToolExecutions(
                    pendingRequiredTools,
                    metadataStringList(metadata, "requiredToolNames"),
                    metadataStringList(metadata, "workflowMandatoryTools")
                )
            );
            rewriteRequirements.addAll(evidenceRefinementRequiredTools(evidenceHistory, tools));
            InterpretationPlanRewriter.RewriteResult rewrite = rewriter.rewrite(new InterpretationPlanRewriter.RewriteRequest(
                currentPlan,
                failedStep,
                evidenceRewriteReason(currentResult, evidenceHistory),
                observations,
                tools,
                toolRegistry,
                rewriteRequirements,
                evidenceHistory
            ));
            metadata.put("interpretationPlanRewriteAttempted", true);
            metadata.put("interpretationPlanRewriteCount", rewriteCount);
            metadata.put("interpretationPlanRewriteValid", rewrite.valid());
            metadata.put("interpretationPlanRewriteExecutable", rewrite.executable());
            if (rewrite.errorMessage() != null && !rewrite.errorMessage().isBlank()) {
                metadata.put("interpretationPlanRewriteError", rewrite.errorMessage());
            }
            recordPlanEvolution(
                currentPlan,
                rewrite.rewrittenPlan(),
                rewriteCount + 1,
                rewrite.valid() ? "ACCEPTED" : "REJECTED",
                evidenceHistory,
                runtimeAttributes,
                metadata
            );
            runtimeGuard.checkCancelled(cancellationCheck);

            String rewriteStage = rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount;
            recordInterpretationPlanEvaluation(
                rewriteStage,
                rewrite.validation(),
                runtimeAttributes,
                metadata
            );
            if (!rewrite.valid() || rewrite.rewrittenPlan() == null) {
                String evaluationError = firstNonBlank(
                    rewrite.errorMessage(),
                    "rewriter did not return a valid plan"
                );
                observations.add("InterpretationPlan " + rewriteStage
                    + " failed plan evaluation and was not executed: " + evaluationError);
                currentResult = planEvaluationFailure(
                    rewriteStage,
                    rewrite.validation(),
                    evaluationError
                );
                if (rewrite.rewrittenPlan() != null) {
                    currentPlan = rewrite.rewrittenPlan();
                }
                planAttemptResults.add(currentResult);
                continue;
            }

            if (ToolCallFingerprint.materiallyEquivalent(currentPlan, rewrite.rewrittenPlan())) {
                duplicateToolPlanSuppressed = true;
                metadata.put("duplicateToolPlanSuppressed", true);
                metadata.put("duplicateToolPlanStage", rewriteStage);
                metadata.put("duplicateToolPlanFingerprints", ToolCallFingerprint.forPlan(rewrite.rewrittenPlan()));
                observations.add("InterpretationPlan " + rewriteStage
                    + " was not executed because its tool calls have no material input change from the previous evidence round.");
                break;
            }

            currentPlan = rewrite.rewrittenPlan();
            saveInterpretationPlanSnapshot(
                rewriteStage,
                currentPlan,
                tenantId,
                requestId,
                runtimeAttributes,
                metadata
            );
            recordInterpretationPlanExecutionStarted(rewriteStage, currentPlan, runtimeAttributes, metadata);
            currentResult = runtime.execute(planExecutionRequest(
                currentPlan,
                tenantId,
                requestId,
                conversationId,
                userId,
                tools,
                workflowAttemptAttributes(runtimeAttributes, rewriteCount)
            ));
            recordPlanRuntimeResult(rewriteStage, currentResult, traces, observations, metadata);
            saveInterpretationPlanSnapshot(
                rewriteStage + "_result",
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
            InterpretationPlanWorkflowGuard.GuardResult currentWorkflowGuard = interpretationPlanWorkflowGuard.evaluate(
                currentPlan,
                currentResult,
                metadataStringList(metadata, "mandatoryTools"),
                metadataStringList(runtimeAttributes, "workflowCompletedTools")
            );
            if (currentResult.success() && !currentWorkflowGuard.allowed()) {
                currentResult = blockIncompleteWorkflow(
                    rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                    currentResult,
                    currentWorkflowGuard,
                    observations,
                    metadata
                );
            }
            currentResult = rejectUnsatisfiedInterpretationPlanResult(
                rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                currentResult,
                observations,
                metadata
            );
            planAttemptResults.add(currentResult);
            Map<String, Object> currentEvidence = analyzeInterpretationPlanEvidence(
                activeChatModel, query, systemPrompt, currentPlan, currentResult, rewriteCount + 1,
                evidenceHistory, runtimeAttributes, metadata, cancellationCheck
            );
            evidenceHistory.add(currentEvidence);
            if (currentResult.success() && evidenceSufficient(currentEvidence)) {
                recordEvidenceStopState(metadata, currentEvidence, "evidence_sufficient", rewriteCount + 1);
                recordMandatoryWorkflowCompletion(traces, metadata, runtimeAttributes);
                String synthesizedAnswer = synthesizeInterpretationPlanAnswer(
                    activeChatModel,
                    query,
                    systemPrompt,
                    currentResult,
                    planAttemptResults,
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
                    "evidence_sufficient"
                );
            }
        }

        metadata.put("interpretationPlanRewriteBudgetExceeded", !duplicateToolPlanSuppressed
            && (maxRewriteTimes <= 0
                || firstInteger(metadata.get("interpretationPlanRewriteCount"), 0) >= maxRewriteTimes));
        metadata.put("interpretationPlanFallbackMode", fallbackMode(plan));
        metadata.put("stopReason", duplicateToolPlanSuppressed
            ? "duplicate_tool_plan_suppressed"
            : "interpretation_plan_evidence_exhausted");
        metadata.put("interpretationPlanEvidenceIterationCount", evidenceHistory.size());
        if (!evidenceHistory.isEmpty()) {
            recordEvidenceStopState(
                metadata,
                evidenceHistory.get(evidenceHistory.size() - 1),
                duplicateToolPlanSuppressed ? "duplicate_tool_plan_suppressed" : "evidence_iteration_limit",
                evidenceHistory.size()
            );
        }
        observations.add(duplicateToolPlanSuppressed
            ? "InterpretationPlan stopped before a duplicate tool call; final answer will use the persisted evidence chain."
            : "InterpretationPlan completed its evidence revision budget; final answer will reconcile all persisted evidence and unresolved gaps.");
        runMissingMandatoryWorkflowTools(
            traces,
            observations,
            query,
            conversationId,
            requestId,
            userId,
            tenantId,
            tools,
            metadataStringList(metadata, "mandatoryTools"),
            documentIds,
            documentTags,
            webSearchResultLimit,
            metadata,
            runtimeAttributes,
            maxToolCalls
        );
        if (Boolean.TRUE.equals(metadata.get("confirmationRequired"))) {
            return answerFinalizer.finishExecution("", traces, metadata, observations);
        }
        if (Boolean.TRUE.equals(metadata.get("toolBudgetExceeded"))) {
            return answerFinalizer.finishBudgetedSummary(activeChatModel, query, systemPrompt, traces, metadata, observations, cancellationCheck);
        }
        AgentExecutionResult blockedResult = finishMandatoryWorkflowBlockedIfPending(
            activeChatModel,
            query,
            systemPrompt,
            traces,
            metadata,
            observations,
            runtimeAttributes,
            cancellationCheck,
            "mandatory_workflow_incomplete",
            "InterpretationPlan failed and mandatory workflow tools are still incomplete."
        );
        if (blockedResult != null) {
            return blockedResult;
        }
        if (!planAttemptResults.isEmpty()) {
            String synthesizedAnswer = synthesizeInterpretationPlanAnswer(
                activeChatModel,
                query,
                systemPrompt,
                planAttemptResults.get(planAttemptResults.size() - 1),
                planAttemptResults,
                runtimeAttributes,
                observations,
                metadata,
                cancellationCheck,
                "attempts_exhausted"
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
                "evidence_iteration_limit"
            );
        }
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

    private AgentExecutionResult finishMandatoryWorkflowBlockedIfPending(ChatModel activeChatModel,
                                                                         String query,
                                                                         String systemPrompt,
                                                                         List<InteractionToolTrace> traces,
                                                                         Map<String, Object> metadata,
                                                                         List<String> observations,
                                                                         Map<String, Object> runtimeAttributes,
                                                                         BooleanSupplier cancellationCheck,
                                                                         String stopReason,
                                                                         String reason) {
        List<String> mandatoryTools = metadataStringList(metadata, "mandatoryTools");
        if (mandatoryTools.isEmpty()) {
            return null;
        }
        Set<String> completedTools = completedWorkflowToolsFromEvents(
            runtimeAttributes,
            workflowStateTracker.completedToolsFromTraces(traces)
        );
        List<String> missingMandatoryTools = workflowTools.missingMandatoryTools(mandatoryTools, completedTools);
        if (missingMandatoryTools.isEmpty()) {
            metadata.put("missingMandatoryTools", List.of());
            metadata.put("mandatoryWorkflowCompleted", true);
            metadata.put("mandatoryWorkflowPending", false);
            return null;
        }
        metadata.put("stopReason", stopReason);
        metadata.put("mandatoryWorkflowBlocked", true);
        metadata.put("fatalExecutionBlocked", true);
        metadata.put("errorCode", "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED");
        metadata.put("errorMessage", "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED: " + reason
            + " Missing mandatory tools: " + missingMandatoryTools);
        metadata.put("missingMandatoryTools", missingMandatoryTools);
        metadata.put("mandatoryWorkflowCompleted", false);
        metadata.put("mandatoryWorkflowPending", true);
        observations.add(reason + " Missing mandatory tools: " + missingMandatoryTools);
        observations.add("PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED: Required tools were not executed to a terminal observation. Missing tools: "
            + String.join(", ", missingMandatoryTools)
            + ".");
        metadata.put("failureSummaryRequiresToolCompletionContext", true);
        return answerFinalizer.finishReviewedSummary(
            activeChatModel,
            query,
            systemPrompt,
            traces,
            metadata,
            observations,
            cancellationCheck,
            stopReason
        );
    }

    private InterpretationPlanRuntime.ExecutionRequest planExecutionRequest(InterpretationPlan plan,
                                                                            String tenantId,
                                                                            String requestId,
                                                                            String conversationId,
                                                                            String userId,
                                                                            List<String> tools,
                                                                            Map<String, Object> runtimeAttributes) {
        Map<String, Object> executionAttributes = new LinkedHashMap<>(runtimeAttributes == null ? Map.of() : runtimeAttributes);
        executionAttributes.put("requireTemplateParameterProtocol", true);
        return new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            tools,
            tenantId,
            requestId,
            conversationId,
            userId,
            executionAttributes
        );
    }

    private Map<String, Object> workflowAttemptAttributes(Map<String, Object> runtimeAttributes, int attempt) {
        Map<String, Object> attributes = new LinkedHashMap<>(runtimeAttributes == null ? Map.of() : runtimeAttributes);
        attributes.put("workflowExecutionAttempt", Math.max(0, attempt));
        attributes.put("toolResultReviewMaxAttempts", 1);
        return attributes;
    }

    private Map<String, Object> runtimeExecutionPolicy(boolean requireToolBeforeFinal) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("userSelectedToolsMustRun", requireToolBeforeFinal);
        policy.put("modelCanSuggestSkip", false);
        policy.put("finalAnswerRequiresToolCompletion", requireToolBeforeFinal);
        policy.put("factGroundingContractVersion", AgentRuntimeFactGroundingContract.CONTRACT_VERSION);
        return policy;
    }

    private List<Map<String, Object>> requiredToolExecutionContracts(List<String> tools,
                                                                     List<String> userSelectedTools,
                                                                     List<String> workflowMandatoryTools) {
        List<Map<String, Object>> contracts = new ArrayList<>();
        for (InterpretationPlanRewriter.RequiredToolExecution execution : requiredToolExecutions(
            tools,
            userSelectedTools,
            workflowMandatoryTools
        )) {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("toolName", execution.toolName());
            contract.put("source", execution.source());
            contract.put("required", execution.required());
            contracts.add(contract);
        }
        return contracts;
    }

    private List<InterpretationPlanRewriter.RequiredToolExecution> requiredToolExecutions(List<String> tools,
                                                                                          List<String> userSelectedTools,
                                                                                          List<String> workflowMandatoryTools) {
        List<InterpretationPlanRewriter.RequiredToolExecution> executions = new ArrayList<>();
        for (String tool : normalizeList(tools)) {
            executions.add(new InterpretationPlanRewriter.RequiredToolExecution(
                tool,
                requiredToolSource(tool, userSelectedTools, workflowMandatoryTools),
                true
            ));
        }
        return executions;
    }

    private String requiredToolSource(String tool,
                                      List<String> userSelectedTools,
                                      List<String> workflowMandatoryTools) {
        if (containsSameTool(userSelectedTools, tool)) {
            return "USER_SELECTED";
        }
        if (containsSameTool(workflowMandatoryTools, tool)) {
            return "RUNTIME_WORKFLOW";
        }
        return "RUNTIME_POLICY";
    }

    private boolean containsSameTool(List<String> tools, String expectedTool) {
        if (expectedTool == null || expectedTool.isBlank() || tools == null || tools.isEmpty()) {
            return false;
        }
        for (String tool : tools) {
            if (toolNames.sameToolName(tool, expectedTool)) {
                return true;
            }
        }
        return false;
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
            ModelProtocolJson.prettyJsonForLog(raw));
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
        String reviewAnswer = firstNonBlank(
            stringValue(firstObject(payload, "review_answer", "reviewAnswer")),
            stringValue(firstObject(payload, "final_answer", "finalAnswer", "answer"))
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("raw", preview(raw));
        metadata.put("controllerPhase", "llm_decision");
        if (reviewAnswer != null && !reviewAnswer.isBlank()) {
            metadata.put("reviewAnswer", reviewAnswer);
        }
        if (firstObject(payload, "final_answer", "finalAnswer") != null) {
            metadata.put("legacyFinalAnswerFieldIgnored", true);
        }
        Object confidence = firstObject(payload, "confidence", "score");
        if (confidence != null) {
            metadata.put("confidence", confidence);
        }
        Object parameterProtocols = firstObject(payload, "parameter_protocols", "parameterProtocols");
        if (parameterProtocols instanceof List<?> protocols) {
            metadata.put("parameterProtocols", protocols);
        }
        return new InterpretationPlanRuntime.DagDecision(protocolVersion, action, stepIds, reason, null, metadata);
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
        prompt.append("- completed_step_ids and executionLock are authoritative runtime state. Never re-run or override a completed/locked step, even if you think the state is contradictory.\n");
        prompt.append("- Do not select a step until all of its depends_on steps are in completed_step_ids.\n");
        prompt.append("- Use execute_parallel_steps only when execution_policy.allow_parallel is true and every selected step is independently ready.\n");
        prompt.append("- Select the final_answer step only when it is the last remaining executable step and evidence is sufficient.\n");
        prompt.append("- Do not emit final_answer in your JSON. If you need to leave a diagnostic, write review_answer; Java will produce final_answer only from the final plan step.\n");
        prompt.append("- If a required dependency failed, request rewrite_plan or abort instead of forcing a dependent step.\n");
        prompt.append("- After template discovery, inspect the selected template's parameterSchema/requiredParameters before selecting its execution step.\n");
        prompt.append("- When the selected template declares parameters, extract only values supported by the current User query and return them in parameter_protocols using template_parameter_protocol_v1. Use the exact declared parameter names and exact discovered template_id.\n");
        prompt.append("- Every model-extracted argument must be {value, source: user_query, evidence}. Never copy parameterSchema, requiredParameters, defaults, routing fields, or an entire template object into arguments. Runtime applies defaults and compiles the concrete MCP request.\n");
        prompt.append("- Put parameters that cannot be obtained from the User query in unresolved_parameters. When a required parameter is unresolved and no completed dependency supplies it, request rewrite_plan instead of executing with an invented or empty value.\n");
        prompt.append("- Do not call tools directly; Java will only execute the step ids you choose after safety validation.\n\n");
        prompt.append("User query:\n").append(query == null ? "" : query).append("\n\n");
        prompt.append("decision_count: ").append(request.decisionCount()).append("\n");
        prompt.append("remaining_step_ids: ").append(request.remainingStepIds() == null ? List.of() : request.remainingStepIds()).append("\n");
        prompt.append("completed_step_ids: ").append(request.completedStepIds() == null ? List.of() : request.completedStepIds()).append("\n");
        prompt.append("current_review_answer_hint: ").append(firstNonBlank(request.finalAnswer(), "")).append("\n\n");
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
                                                      List<InterpretationPlanRuntime.ExecutionResult> attemptResults,
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
                "attemptCount", attemptResults == null ? 0 : attemptResults.size(),
                "storedObservationCount", storedObservations.size()
            )
        );
        SqlMetadataAnswerRenderer.RenderedSqlMetadata renderedSqlMetadata = sqlMetadataAnswerRenderer.renderEvidence(result);
        String structuredSqlMetadata = renderedSqlMetadata.markdown();
        String prompt = buildInterpretationPlanSummaryPrompt(
            query,
            systemPrompt,
            result,
            attemptResults,
            observations,
            storedObservations,
            structuredSqlMetadata
        );
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
            ModelProtocolJson.prettyJsonForLog(answer));
        if (metadata != null) {
            metadata.put("interpretationPlanSummaryGenerated", true);
            metadata.put("interpretationPlanSummaryStage", stage);
            metadata.put("interpretationPlanAttemptCount", attemptResults == null ? 0 : attemptResults.size());
            metadata.put("interpretationPlanStoredObservationCount", storedObservations.size());
        }
        if (!structuredSqlMetadata.isBlank()) {
            if (metadata != null) {
                metadata.put("structuredSqlMetadataRendered", true);
                metadata.put("structuredSqlMetadataMarkdown", structuredSqlMetadata);
                metadata.put("structuredSqlMetadataPreview", preview(structuredSqlMetadata));
                metadata.put("sqlMetadataFact", renderedSqlMetadata.metadata());
                metadata.put("sqlMetadataSemanticGatePassed", renderedSqlMetadata.metadata().get("semanticGatePassed"));
                metadata.put("sqlMetadataSemanticGateReason", renderedSqlMetadata.metadata().get("semanticGateReason"));
                Map<String, Object> graphSemanticState = executionGraphSemanticValidator.validate(query, result, renderedSqlMetadata.metadata());
                metadata.put("executionGraphSemanticState", graphSemanticState);
                metadata.put("executionGraphSemanticPassed", graphSemanticState.get("passed"));
                metadata.put("executionGraphSemanticReason", graphSemanticState.get("reason"));
            }
            if ("mcp_sql_metadata_search_catalog".equals(renderedSqlMetadata.metadata().get("source"))) {
                answer = rewriteUngroundedSqlMetadataSummary(
                    activeChatModel,
                    query,
                    structuredSqlMetadata,
                    renderedSqlMetadata.metadata(),
                    answer,
                    metadata
                );
                if (metadata != null) {
                    metadata.put("sqlMetadataGroundingValidated", true);
                }
            }
            answer = mergeStructuredSqlMetadataAnswer(structuredSqlMetadata, answer);
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

    private String mergeStructuredSqlMetadataAnswer(String structuredSqlMetadata, String modelAnswer) {
        if (structuredSqlMetadata == null || structuredSqlMetadata.isBlank()) {
            return modelAnswer == null ? "" : modelAnswer;
        }
        String answer = modelAnswer == null ? "" : modelAnswer.trim();
        if (answer.contains(structuredSqlMetadata.trim())) {
            return answer;
        }
        if (answer.isBlank()) {
            return structuredSqlMetadata;
        }
        return structuredSqlMetadata.trim() + "\n\n## 分析结论\n\n" + answer;
    }

    private String rewriteUngroundedSqlMetadataSummary(ChatModel activeChatModel,
                                                       String query,
                                                       String structuredEvidence,
                                                       Map<String, Object> factMetadata,
                                                       String draft,
                                                       Map<String, Object> runtimeMetadata) {
        String summary = draft == null ? "" : draft.trim();
        List<String> violations = sqlMetadataGroundingGuard.violations(summary, factMetadata);
        int rewriteCount = 0;
        while (!violations.isEmpty() && rewriteCount < 2 && activeChatModel != null) {
            rewriteCount++;
            summary = activeChatModel.chat(sqlMetadataGroundingGuard.rewritePrompt(
                query,
                structuredEvidence,
                summary,
                violations
            ));
            summary = summary == null ? "" : summary.trim();
            violations = sqlMetadataGroundingGuard.violations(summary, factMetadata);
        }
        if (runtimeMetadata != null) {
            runtimeMetadata.put("sqlMetadataGroundingRewriteCount", rewriteCount);
            runtimeMetadata.put("sqlMetadataGroundingViolations", violations);
        }
        if (!violations.isEmpty()) {
            return sqlMetadataGroundingGuard.safeSummary();
        }
        return summary;
    }

    String buildInterpretationPlanSummaryPrompt(String query,
                                                String systemPrompt,
                                                InterpretationPlanRuntime.ExecutionResult result,
                                                List<String> observations,
                                                List<AgentObservation> storedObservations,
                                                String authoritativeSqlMetadata) {
        return buildInterpretationPlanSummaryPrompt(
            query,
            systemPrompt,
            result,
            result == null ? List.of() : List.of(result),
            observations,
            storedObservations,
            authoritativeSqlMetadata
        );
    }

    String buildInterpretationPlanSummaryPrompt(String query,
                                                String systemPrompt,
                                                InterpretationPlanRuntime.ExecutionResult result,
                                                List<InterpretationPlanRuntime.ExecutionResult> attemptResults,
                                                List<String> observations,
                                                List<AgentObservation> storedObservations,
                                                String authoritativeSqlMetadata) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are the final step-by-step answer synthesizer for MCP InterpretationPlan attempts.\n");
        prompt.append("Answer the user in Chinese using only the executed attempt records, model review decisions, and stored observations.\n");
        prompt.append("Return a polished Markdown document, not a single plain paragraph. Use concise headings and lists when they improve readability.\n");
        prompt.append("Do not wrap the Markdown in code fences and do not output JSON.\n");
        prompt.append("Workflow contract:\n");
        prompt.append("- Treat every succeeded tool step with returned data as evidence, even when the model review marked it incomplete or partial.\n");
        prompt.append("- When multiple plan attempts were executed, reconcile and summarize evidence from all attempts; prefer the latest complete result when evidence conflicts.\n");
        prompt.append("- Treat interpretation_evidence_iteration_v1 snapshots as the evidence chain: preserve their evidenceId-to-conclusion basis, missingEvidence, conflicts, and nextActions.\n");
        prompt.append("- Treat hypotheses as testable explanations. Explain which hypothesis is supported, contradicted, or unresolved and bind every claim to supportEvidenceIds or contradictEvidenceIds.\n");
        prompt.append("- A hypothesis statement is never evidence by itself. Its confidence and status must be justified by persisted Evidence Objects.\n");
        prompt.append("- Use hypothesis_tree_v1 parent/child relationships to explain broad conclusions through independently validated sub-hypotheses without flattening unresolved children into a supported parent.\n");
        prompt.append("- Use evidence_quality_v1 as independent quality dimensions. Each dimension has value/status/type/reason; UNKNOWN means not assessed and must never be interpreted as 0.5. modelConfidence is MODEL_ESTIMATED and is not an evidence-quality score.\n");
        prompt.append("- Use evidence_graph_v1 as the authoritative Evidence-to-Hypothesis relationship layer. Only ACTIVE SUPPORTS or CONTRADICTS relations with existing Evidence nodes may justify a hypothesis; rejectedRelations are audit findings, not evidence.\n");
        prompt.append("- Use plan_evolution_v1 only to explain why the runtime changed its retrieval or execution path. A plan change is not evidence for the user's factual answer.\n");
        prompt.append("- The final answer must be grounded in the cumulative MCP results from every iteration. Do not treat an intermediate model conclusion as evidence unless its referenced evidenceId exists in an executed tool result.\n");
        prompt.append("- Resolve conflicts explicitly. If three iterations still leave a material gap, report that gap instead of filling it with model knowledge.\n");
        prompt.append("- Do not hide earlier partial or failed attempts when they contain usable evidence. State unresolved limitations after considering all attempts.\n");
        prompt.append("- Use each step's review reason as the premise for later steps.\n");
        prompt.append("- Summarize what was done step by step, then provide the final answer.\n");
        prompt.append("- If a succeeded SQL/database step is partial, still summarize the returned rows/metrics and explicitly state missing fields or limitations.\n");
        prompt.append("- If some step truly failed with no usable output, state the limitation and do not use that failed result as evidence.\n");
        prompt.append("- Do not display executed SQL statements, scripts, or query text in the user-facing answer. Mention only the template/tool and returned data.\n");
        prompt.append("- A shortened output preview in this prompt is not evidence that the tool output itself was truncated. Claim truncation only when output facts contain explicitTruncation=true or the output has an explicit _truncated/[truncated] marker.\n");
        prompt.append("- Do not invent facts that are not present in the step outputs or observations.\n");
        prompt.append("- For SQL metadata discovery, cite every recommended table by the exact physical identifier returned by the tool (database/schema/tableName when available). Keep any Chinese business description separate; never use it as a substitute for the physical table name.\n");
        prompt.append("- When returned metadata includes columns, list the exact physical column names under their corresponding physical table and preserve returned type/key/comment details. Never translate, rename, or invent identifiers.\n");
        prompt.append("- If column metadata was not returned for a matched table, say so explicitly instead of presenting illustrative fields as facts.\n\n");
        prompt.append("- Database layering labels (for example ADS/DWS/DWD/DIM), table names, schemas, databases, and fields are evidence facts only when the current tool output explicitly returned them. Never infer a layer from a naming convention. Never output 'possible table examples', 'common tables', or supplemental table recommendations that were not retrieved.\n");
        prompt.append(AgentRuntimeFactGroundingContract.promptSection());
        if (authoritativeSqlMetadata != null && !authoritativeSqlMetadata.isBlank()) {
            prompt.append("\nAuthoritative SQL metadata fact block (non-overridable):\n")
                .append(authoritativeSqlMetadata)
                .append("\n\n")
                .append("Summarize and explain this fact block for the user's goal. Do not alter its identifiers, counts, completeness state, table/field descriptions, or introduce additional database objects.\n");
        }
        prompt.append("User query:\n").append(query == null ? "" : query).append("\n\n");
        if (result != null && result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            prompt.append("Plan final answer hint, not authoritative evidence:\n")
                .append(result.finalAnswer())
                .append("\n\n");
        }
        List<InterpretationPlanRuntime.ExecutionResult> results = attemptResults == null || attemptResults.isEmpty()
            ? (result == null ? List.of() : List.of(result))
            : attemptResults;
        prompt.append("Executed plan attempts (").append(results.size()).append("):\n");
        if (results.isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            for (int attemptIndex = 0; attemptIndex < results.size(); attemptIndex++) {
                InterpretationPlanRuntime.ExecutionResult attemptResult = results.get(attemptIndex);
                prompt.append("\nAttempt ").append(attemptIndex + 1)
                    .append(": status=").append(attemptResult.status())
                    .append(", success=").append(attemptResult.success())
                    .append("\n");
                if (attemptResult.errorMessage() != null && !attemptResult.errorMessage().isBlank()) {
                    prompt.append("  attemptError: ").append(attemptResult.errorMessage()).append("\n");
                }
                if (attemptResult.steps() == null || attemptResult.steps().isEmpty()) {
                    prompt.append("  - (no executed steps)\n");
                    continue;
                }
                for (InterpretationPlanRuntime.StepExecution step : attemptResult.steps()) {
                prompt.append("- evidenceId=iteration:").append(attemptIndex + 1)
                    .append(":step:").append(step.stepId())
                    .append(":tool:").append(firstNonBlank(step.toolName(), step.actionType()))
                    .append(", step=").append(step.stepId())
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
                Map<String, Object> outputFacts = structuredOutputFacts(step.output());
                if (!outputFacts.isEmpty()) {
                    prompt.append("  outputFacts: ")
                        .append(shortObservationText(stringify(outputFacts), 1800))
                        .append("\n");
                }
                String executionEvidence = step.success()
                    ? toolObservationBuilder.buildAuthoritativeExecutionEvidence(step.toolName(), step.output())
                    : null;
                if (executionEvidence != null && !executionEvidence.isBlank()) {
                    prompt.append("  authoritativeToolResultEvidence (complete runtime-returned data; operation inputs omitted):\n")
                        .append(executionEvidence)
                        .append("\n  promptPreviewTruncated=false\n");
                } else {
                    String serializedOutput = stringify(redactExecutionStatementText(step.output()));
                    boolean promptPreviewTruncated = serializedOutput != null && serializedOutput.replaceAll("\\s+", " ").trim().length() > 4000;
                    prompt.append("  outputPreview: ")
                        .append(shortObservationText(serializedOutput, 4000))
                        .append("\n  promptPreviewTruncated=")
                        .append(promptPreviewTruncated)
                        .append("\n");
                }
            }
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
                .append(observation)
                .append("\n"));
        }
        prompt.append("\nReturn only the final user-facing Markdown answer, no JSON.");
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
            ModelProtocolJson.prettyJsonForLog(raw));
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
        String reviewAnswer = firstNonBlank(
            stringValue(firstObject(payload, "review_answer", "reviewAnswer")),
            stringValue(firstObject(payload, "final_answer", "finalAnswer", "answer"))
        );
        if (reviewAnswer != null && !reviewAnswer.isBlank()) {
            metadata.put("reviewAnswer", reviewAnswer);
        }
        if (firstObject(payload, "final_answer", "finalAnswer") != null) {
            metadata.put("reviewFinalAnswerFieldIgnored", true);
        }
        List<String> selectedUrls = stringList(firstObject(payload, "selected_urls", "selectedUrls", "urls"));
        if (!selectedUrls.isEmpty()) {
            metadata.put("selectedUrls", selectedUrls);
        }
        List<String> usefulRefs = stringList(firstObject(payload, "useful_refs", "usefulRefs", "selected_refs", "selectedRefs"));
        if (!usefulRefs.isEmpty()) {
            metadata.put("usefulEvidenceRefs", usefulRefs);
        }
        List<String> rejectedRefs = stringList(firstObject(payload, "rejected_refs", "rejectedRefs", "irrelevant_refs", "irrelevantRefs"));
        if (!rejectedRefs.isEmpty()) {
            metadata.put("rejectedEvidenceRefs", rejectedRefs);
        }
        List<String> selectedTemplateIds = stringList(firstObject(payload, "selected_template_ids", "selectedTemplateIds"));
        if (!selectedTemplateIds.isEmpty()) {
            metadata.put("selectedTemplateIds", selectedTemplateIds);
        }
        List<String> rejectedTemplateIds = stringList(firstObject(payload, "rejected_template_ids", "rejectedTemplateIds"));
        if (!rejectedTemplateIds.isEmpty()) {
            metadata.put("rejectedTemplateIds", rejectedTemplateIds);
        }
        String refinedIntent = stringValue(firstObject(payload, "refined_intent", "refinedIntent"));
        if (refinedIntent != null && !refinedIntent.isBlank()) {
            metadata.put("refinedIntent", refinedIntent);
        }
        Object iterationSufficient = firstObject(payload,
            "iteration_sufficient", "iterationSufficient", "evidence_sufficient", "evidenceSufficient");
        if (iterationSufficient != null) {
            metadata.put("evidenceIterationSufficient", booleanValue(iterationSufficient));
        }
        Object evidenceBasis = firstObject(payload, "evidence_used", "evidenceUsed", "basis", "based_on");
        if (evidenceBasis != null) {
            metadata.put("evidenceBasis", evidenceBasis);
        }
        Object missingEvidence = firstObject(payload,
            "missing_evidence", "missingEvidence", "missingAspects", "missing_aspects");
        if (missingEvidence != null) {
            metadata.put("missingEvidence", missingEvidence);
        }
        Object evidenceConflicts = firstObject(payload, "conflicts", "contradictions", "uncertainty");
        if (evidenceConflicts != null) {
            metadata.put("evidenceConflicts", evidenceConflicts);
        }
        Object nextActions = firstObject(payload,
            "next_actions", "nextActions", "next_queries", "nextQueries", "query_revisions", "queryRevisions");
        if (nextActions != null) {
            metadata.put("nextActions", nextActions);
        }
        Object hypotheses = firstObject(payload, "hypotheses", "hypothesis", "hypothesis_state", "hypothesisState");
        if (hypotheses != null) {
            metadata.put("hypotheses", hypotheses);
        }
        Object confidence = firstObject(payload, "confidence", "score");
        if (confidence != null) {
            metadata.put("toolResultReviewConfidence", confidence);
        }
        Map<String, Object> evidenceEvaluation = evidenceEvaluationContract(payload, satisfied, reason, usefulRefs, rejectedRefs, confidence);
        if (!evidenceEvaluation.isEmpty()) {
            metadata.put("evidenceEvaluation", evidenceEvaluation);
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
        prompt.append("{\"satisfied\":true|false,\"iteration_sufficient\":true|false,\"reason\":\"short reason\",\"review_answer\":\"optional audit note, not user-facing final answer\",\"evidence_used\":[{\"basis\":\"returned fact\"}],\"missing_evidence\":[\"material gap\"],\"conflicts\":[\"conflict\"],\"hypotheses\":[{\"hypothesis_id\":\"H1\",\"parent_hypothesis_id\":null,\"statement\":\"testable explanation\",\"support_evidence_ids\":[],\"contradict_evidence_ids\":[],\"confidence\":0.0,\"status\":\"SUPPORTED|CONTRADICTED|UNRESOLVED\"}],\"next_actions\":[{\"tool\":\"available_tool_name\",\"intent\":\"evidence gap to close or hypothesis to test\",\"input_changes\":{\"parameter\":\"revised value\"},\"reason\":\"why this action is needed\",\"based_on\":[\"evidenceId\",\"hypothesisId\"]}],\"selected_urls\":[\"https://...\"],\"useful_refs\":[\"doc://...#chunk=0\"],\"rejected_refs\":[\"doc://...#chunk=1\"],\"selected_template_ids\":[\"template-id\"],\"rejected_template_ids\":[\"template-id\"],\"refined_intent\":\"optional refined retrieval intent\",\"relevance\":0.0,\"answerability\":0.0,\"supportsQuestionAspect\":[\"process\"],\"missingAspects\":[\"constraints\"],\"usefulness\":\"HIGH|MEDIUM|LOW\",\"shouldExpandQuery\":true|false,\"confidence\":0.0}\n");
        prompt.append("Rules:\n");
        prompt.append(AgentRuntimeFactGroundingContract.promptSection());
        prompt.append("- Decide whether this tool output is sufficient for the current plan step and user request.\n");
        prompt.append("- iteration_sufficient evaluates the cumulative user request, not merely whether this one tool call technically succeeded. Set it false when material evidence is still missing and provide evidence_used, missing_evidence, conflicts, and tool-agnostic next_actions.\n");
        prompt.append("- next_actions may revise the current tool input, call another available tool, validate a conflict, or retrieve a missing fact. Do not assume any particular tool type.\n");
        prompt.append("- hypotheses must be testable explanations, not facts. Mark each SUPPORTED, CONTRADICTED, or UNRESOLVED and relate it to returned evidence. Runtime will bind the current evidenceId when the model cannot know it yet.\n");
        prompt.append("- Preserve a hypothesis_id when the same hypothesis is refined later; create a new id only for a materially different explanation.\n");
        prompt.append("- Use parent_hypothesis_id to decompose a broad hypothesis into independently testable child hypotheses. Do not create cycles or make a hypothesis its own parent.\n");
        prompt.append("- If satisfied=false, explain missing aspects, but never discard succeeded SQL/database rows merely because they are partial or imperfect.\n");
        prompt.append("- For SQL/database outputs, any returned rows, columns, metrics, or result sets are usable partial evidence. Mark them satisfied=true when they can support any part of the answer, and list gaps in missingAspects.\n");
        prompt.append("- For web discovery tools (web_search, web_page_analyze, site_intelligence_resolver, *_site_search), judge candidate URLs/snippets only. Do not require full article content from these tools.\n");
        prompt.append("- If a web discovery tool returns useful URLs for follow-up crawling or page analysis, set satisfied=true and put those URLs in selected_urls.\n");
        prompt.append("- For crawl/content tools, judge whether the fetched full content is relevant and usable for analysis.\n");
        prompt.append("- For document_search, judge whether the result contains relevant document evidence that can support later synthesis. Do not require one chunk to contain the complete final answer or every requested example.\n");
        prompt.append("- Accept document_search when multiple chunks collectively mention relevant entities, APIs, tables, citations, or snippets, even if the final answer must combine them and state missing pieces.\n");
        prompt.append("- Reject document_search only when it failed, returned no useful results, violated an explicit source constraint, or is unrelated to the request.\n");
        prompt.append("- For document_search, evaluate each returned document/chunk against the current user request. Put useful doc:// refs in useful_refs and unrelated or misleading refs in rejected_refs. Do not infer usefulness from retrieval rank alone.\n");
        prompt.append("- Treat retrieval score as a weak prior only. Your semantic evidence evaluation must state relevance, answerability, supported aspects, missing aspects, usefulness, and whether another query expansion is needed.\n");
        prompt.append("- For template discovery and API/HTTP requirement analysis, compare title, description, capabilitySpec, outputSchema, dependencySpec and required parameters with the current requirement. Return only ids present in the tool output under selected_template_ids/rejected_template_ids. If candidates do not cover the requirement, set satisfied=false and provide refined_intent.\n");
        prompt.append("- If the user required an official source, reject results that do not satisfy that source constraint.\n");
        prompt.append("- Do not answer the user here; only review the tool result.\n\n");
        prompt.append("- Never write final_answer/finalAnswer in this reviewer JSON. If you need to propose wording for audit, write review_answer; it will not become the user-facing answer.\n");
        prompt.append("- Runtime deterministic fact check is non-overridable: do not contradict returned counts or extracted metadata facts. You may still reject for semantic mismatch, wrong template, wrong target, or missing follow-up evidence.\n");
        prompt.append("- The Current-turn user query below is the only user-authored source for an explicitly requested target. Plan intent and Current step are model-generated and must never be described as 'the user specified' unless the exact target text also appears in the current-turn user query.\n");
        prompt.append("- Prefer the tool output's routing/default-asset facts when stating which asset was actually queried. Do not confuse a requested target, datasource asset, database/schema, and table name.\n");
        prompt.append("- If assetDiscoveryReturnedCount > 0, do not claim the asset query returned zero/no assets.\n");
        prompt.append("- If templateDiscoveryReturnedCount > 0, do not claim the template query returned zero/no templates.\n");
        prompt.append("- If sqlMetadataColumnCount > 0, do not claim the SQL metadata step returned no columns/metadata.\n");
        prompt.append("- A shortened Tool output preview is not evidence that the MCP tool returned truncated data. Claim truncation only when Structured output facts contain explicitTruncation=true or the output has an explicit _truncated/[truncated] marker.\n");
        prompt.append("Attempt: ").append(request.attempt()).append('/').append(request.maxAttempts()).append("\n");
        prompt.append("Current-turn user query:\n").append(query == null ? "" : query).append("\n\n");
        InterpretationPlan plan = request.plan();
        prompt.append("Plan intent:\n")
            .append(plan == null || plan.intent() == null ? "" : stringify(plan.intent()))
            .append("\n\n");
        prompt.append("Current step:\n")
            .append(request.step() == null ? "" : stringify(request.step()))
            .append("\n\n");
        Map<String, Object> factMetadata = toolResultFactMetadata(request.execution());
        if (!factMetadata.isEmpty()) {
            prompt.append("Runtime deterministic fact check:\n")
                .append(shortObservationText(stringify(factMetadata), 2500))
                .append("\n\n");
        }
        Map<String, Object> outputFacts = structuredOutputFacts(request.execution().output());
        if (!outputFacts.isEmpty()) {
            prompt.append("Structured output facts:\n")
                .append(shortObservationText(stringify(outputFacts), 2500))
                .append("\n\n");
        }
        prompt.append("Tool output preview, shortened only for reviewer context when necessary:\n")
            .append(shortObservationText(stringify(redactExecutionStatementText(request.execution().output())), 9000));
        return prompt.toString();
    }

    @SuppressWarnings("unchecked")
    private Object redactExecutionStatementText(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (isExecutionStatementKey(key)) {
                    redacted.put(key, "[hidden: execution statement]");
                } else {
                    redacted.put(key, redactExecutionStatementText(child));
                }
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redactExecutionStatementText).toList();
        }
        return value;
    }

    private boolean isExecutionStatementKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.equals("statement")
            || normalized.equals("sql")
            || normalized.equals("script")
            || normalized.equals("sqltemplate")
            || normalized.equals("renderedsql")
            || normalized.equals("querytext");
    }

    private Map<String, Object> toolResultFactMetadata(InterpretationPlanRuntime.StepExecution execution) {
        if (execution == null || execution.metadata() == null || execution.metadata().isEmpty()) {
            return Map.of();
        }
        Set<String> allowedKeys = Set.of(
            "localDecisionPhase",
            "localFactCheckSatisfied",
            "localFactCheckHasEvidence",
            "localFactCheckEvidenceType",
            "localFactCheckReason",
            "assetDiscoveryReturnedCount",
            "templateDiscoveryReturnedCount",
            "sqlMetadataFactChecked",
            "sqlMetadataColumnCount"
        );
        Map<String, Object> facts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : execution.metadata().entrySet()) {
            if (allowedKeys.contains(entry.getKey())) {
                facts.put(entry.getKey(), entry.getValue());
            }
        }
        return facts;
    }

    private Map<String, Object> structuredOutputFacts(Object output) {
        Map<String, Object> root = asMap(output);
        if (root.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        putIfPresent(facts, "schemaVersion", root.get("schemaVersion"));
        putIfPresent(facts, "category", root.get("category"));
        putIfPresent(facts, "dataSchemaVersion", root.get("dataSchemaVersion"));
        putIfPresent(facts, "success", root.get("success"));
        putIfPresent(facts, "status", root.get("status"));
        putIfPresent(facts, "errorMessage", root.get("errorMessage"));

        if (isSqlMetadataSearchResult(root)) {
            putIfPresent(facts, "totalMatched", root.get("totalMatched"));
            putIfPresent(facts, "catalogReturnedCount", root.get("catalogReturnedCount"));
            putIfPresent(facts, "returnedDetailCount", firstNonNull(root.get("detailReturnedCount"), root.get("returnedDetailCount")));
            putIfPresent(facts, "catalogTruncated", root.get("catalogTruncated"));
            putIfPresent(facts, "detailTruncated", root.get("detailTruncated"));
            facts.put("explicitTruncation", Boolean.TRUE.equals(root.get("catalogTruncated")));
            facts.put("truncationSemantics",
                "catalogTruncated controls physical table-name completeness; detailTruncated only controls column-detail completeness");
            return facts;
        }

        Map<String, Object> target = asMap(root.get("target"));
        if (!target.isEmpty()) {
            facts.put("target", compactMap(target, "type", "id", "name", "address", "ipAddress", "toolName", "environment"));
        }

        Map<String, Object> operation = asMap(root.get("operation"));
        if (!operation.isEmpty()) {
            facts.put("operation", compactMap(operation, "type", "template", "commandHash", "sourceTaskId", "reason"));
        }

        Map<String, Object> data = asMap(root.get("data"));
        if (!data.isEmpty()) {
            Map<String, Object> dataFacts = compactMap(
                data,
                "exitCode",
                "transportSuccess",
                "commandSuccess",
                "nonZeroStepIndexes",
                "failedStepIndex",
                "outputMode",
                "rowCount",
                "returnedRowCount",
                "complete",
                "possiblyTruncated",
                "truncationStrategy"
            );
            Map<String, Object> outputLimits = asMap(data.get("outputLimits"));
            if (!outputLimits.isEmpty()) {
                dataFacts.put("outputLimits", outputLimits);
            }
            Integer stdoutLength = outputLength(data.get("stdout"));
            Integer stderrLength = outputLength(data.get("stderr"));
            if (stdoutLength != null) {
                dataFacts.put("stdoutLength", stdoutLength);
            }
            if (stderrLength != null) {
                dataFacts.put("stderrLength", stderrLength);
            }
            Map<String, Object> diagnostics = firstNonEmptyMap(data.get("diagnostics"), operation.get("diagnostics"));
            if (!diagnostics.isEmpty()) {
                dataFacts.put("diagnostics", compactMap(
                    diagnostics,
                    "stdoutLength",
                    "stderrLength",
                    "stepCount",
                    "exitCode",
                    "transportSuccess",
                    "commandSuccess",
                    "nonZeroStepIndexes",
                    "durationMs"
                ));
            }
            facts.put("data", dataFacts);
        }

        facts.put("explicitTruncation", hasExplicitTruncationMarker(output, 0, new Counter()));
        return facts.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> !(entry.getValue() instanceof Map<?, ?> map && map.isEmpty()))
            .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }

    private boolean isSqlMetadataSearchResult(Map<String, Object> root) {
        String schemaVersion = stringValue(root == null ? null : root.get("schemaVersion"));
        return schemaVersion != null && schemaVersion.toLowerCase().contains("sql_metadata_search_result");
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private Map<String, Object> firstNonEmptyMap(Object first, Object second) {
        Map<String, Object> firstMap = asMap(first);
        return firstMap.isEmpty() ? asMap(second) : firstMap;
    }

    private Map<String, Object> compactMap(Map<String, Object> source, String... keys) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (source == null || source.isEmpty() || keys == null) {
            return values;
        }
        for (String key : keys) {
            if (key != null && source.containsKey(key) && source.get(key) != null) {
                values.put(key, source.get(key));
            }
        }
        return values;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target != null && key != null && value != null) {
            target.put(key, value);
        }
    }

    private Integer outputLength(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        return text.length();
    }

    private boolean hasExplicitTruncationMarker(Object value, int depth, Counter counter) {
        if (value == null || depth > 8 || counter.value > 300) {
            return false;
        }
        counter.value++;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object item = entry.getValue();
                if (("_truncated".equals(key)
                    || "possiblyTruncated".equals(key)
                    || key.endsWith("Truncated")) && Boolean.TRUE.equals(item)) {
                    return true;
                }
                if (hasExplicitTruncationMarker(item, depth + 1, counter)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (hasExplicitTruncationMarker(item, depth + 1, counter)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof String text) {
            return text.contains("...[truncated") || text.equals("[truncated]") || text.contains("...<truncated>");
        }
        return false;
    }

    private Map<String, Object> evidenceEvaluationContract(Map<String, Object> payload,
                                                           boolean satisfied,
                                                           String reason,
                                                           List<String> usefulRefs,
                                                           List<String> rejectedRefs,
                                                           Object confidence) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", "evidence_evaluation_contract_v1");
        value.put("satisfied", satisfied);
        value.put("relevance", scoreValue(firstObject(payload, "relevance", "relevanceScore", "semantic_relevance")));
        value.put("answerability", scoreValue(firstObject(payload, "answerability", "answerabilityScore", "can_answer_score")));
        value.put("supportsQuestionAspect", stringList(firstObject(
            payload,
            "supportsQuestionAspect",
            "supports_question_aspect",
            "supportedAspects",
            "supported_aspects"
        )));
        value.put("missingAspects", stringList(firstObject(
            payload,
            "missingAspects",
            "missing_aspects",
            "unsupportedAspects",
            "unsupported_aspects"
        )));
        value.put("usefulness", usefulnessValue(firstObject(payload, "usefulness", "utility", "usefulnessLevel")));
        value.put("usefulRefs", usefulRefs == null ? List.of() : usefulRefs);
        value.put("rejectedRefs", rejectedRefs == null ? List.of() : rejectedRefs);
        value.put("shouldExpandQuery", booleanObject(firstObject(
            payload,
            "shouldExpandQuery",
            "should_expand_query",
            "expandQuery",
            "expand_query"
        )));
        value.put("confidence", scoreValue(confidence));
        value.put("reason", firstNonBlank(reason, stringValue(firstObject(payload, "reason", "feedback", "analysis"))));
        return value;
    }

    private double scoreValue(Object value) {
        if (value instanceof Number number) {
            return clampScore(number.doubleValue());
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return clampScore(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private double clampScore(double value) {
        if (value > 1.0) {
            value = value / 100.0;
        }
        return Math.max(0.0, Math.min(1.0, Math.round(value * 1000.0) / 1000.0));
    }

    private String usefulnessValue(Object value) {
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return "LOW";
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("HIGH".equals(normalized) || "MEDIUM".equals(normalized) || "LOW".equals(normalized)) {
            return normalized;
        }
        return switch (normalized) {
            case "STRONG", "USEFUL", "RELEVANT" -> "HIGH";
            case "PARTIAL", "SOME", "WEAK" -> "MEDIUM";
            default -> "LOW";
        };
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

    private InterpretationPlanRuntime.ExecutionResult rejectUnsatisfiedInterpretationPlanResult(
        String stage,
        InterpretationPlanRuntime.ExecutionResult result,
        List<String> observations,
        Map<String, Object> metadata
    ) {
        if (result == null || !result.success() || result.steps() == null) {
            return result;
        }
        List<String> reasons = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            Map<String, Object> stepMetadata = step == null || step.metadata() == null
                ? Map.of()
                : step.metadata();
            if (Boolean.TRUE.equals(stepMetadata.get("toolResultReviewPartialAccepted"))
                || Boolean.FALSE.equals(stepMetadata.get("toolResultReviewSatisfied"))) {
                reasons.add("step " + step.stepId() + ": " + firstNonBlank(
                    stringValue(stepMetadata.get("toolResultReviewReason")),
                    firstNonBlank(
                        stringValue(stepMetadata.get("toolResultReviewPartialReason")),
                        "result review was not satisfied"
                    )
                ));
            }
        }
        if (reasons.isEmpty()) {
            if (metadata != null) {
                metadata.put("interpretationPlanResultSatisfied", true);
            }
            return result;
        }
        String reason = "Plan attempt did not satisfy result review: " + String.join("; ", reasons);
        if (observations != null) {
            observations.add("InterpretationPlan " + stage + " requires a full plan rewrite. " + reason);
        }
        if (metadata != null) {
            metadata.put("interpretationPlanResultSatisfied", false);
            metadata.put("interpretationPlanUnsatisfiedStage", stage);
            metadata.put("interpretationPlanUnsatisfiedReasons", reasons);
        }
        Map<String, Object> resultMetadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        resultMetadata.put("planResultSatisfied", false);
        resultMetadata.put("planResultUnsatisfiedReasons", reasons);
        return new InterpretationPlanRuntime.ExecutionResult(
            "result_unsatisfied",
            false,
            false,
            reason,
            result.finalAnswer(),
            result.steps(),
            resultMetadata,
            result.durationMs()
        );
    }

    private int maxRewriteTimes(InterpretationPlan plan) {
        return MAX_INTERPRETATION_PLAN_ATTEMPTS - 1;
    }

    private void recordInterpretationPlanEvaluation(
        String stage,
        InterpretationPlanValidator.ValidationResult evaluation,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stage", stage);
        record.put("valid", evaluation != null && evaluation.valid());
        record.put("executable", evaluation != null && evaluation.executable());
        record.put("approvalRequired", evaluation != null && evaluation.approvalRequired());
        record.put("errors", evaluation == null || evaluation.errors() == null ? List.of() : evaluation.errors());
        record.put("warnings", evaluation == null || evaluation.warnings() == null ? List.of() : evaluation.warnings());
        record.put(
            "approvalRequests",
            evaluation == null || evaluation.approvalRequests() == null ? List.of() : evaluation.approvalRequests()
        );
        addCandidateList(metadataList(metadata, "interpretationPlanEvaluations"), List.of(record));
        recordLifecyclePhase(
            runtimeAttributes,
            metadata,
            "plan_evaluation",
            evaluation != null && evaluation.valid()
                ? "InterpretationPlan passed evaluation and may proceed to execution."
                : "InterpretationPlan failed evaluation and will not be executed.",
            record
        );
    }

    private void recordInterpretationPlanExecutionStarted(
        String stage,
        InterpretationPlan plan,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        recordLifecyclePhase(
            runtimeAttributes,
            metadata,
            "step_execution",
            "InterpretationPlan DAG execution started.",
            metadataOf(
                "stage", stage,
                "planVersion", plan == null ? null : plan.version(),
                "stepCount", plan == null || plan.steps() == null ? 0 : plan.steps().size()
            )
        );
    }

    private InterpretationPlanRuntime.ExecutionResult planEvaluationFailure(
        String stage,
        InterpretationPlanValidator.ValidationResult evaluation
    ) {
        return planEvaluationFailure(stage, evaluation, null);
    }

    private InterpretationPlanRuntime.ExecutionResult planEvaluationFailure(
        String stage,
        InterpretationPlanValidator.ValidationResult evaluation,
        String explicitError
    ) {
        String validationErrors = evaluation == null || evaluation.errors() == null || evaluation.errors().isEmpty()
            ? null
            : evaluation.errors().stream()
                .map(issue -> issue.path() + ": " + issue.message())
                .collect(java.util.stream.Collectors.joining("; "));
        String evaluationError = firstNonBlank(
            explicitError,
            firstNonBlank(validationErrors, "plan evaluation rejected the generated plan")
        );
        return new InterpretationPlanRuntime.ExecutionResult(
            "plan_evaluation_failed",
            false,
            false,
            "InterpretationPlan " + stage + " was not executed: " + evaluationError,
            null,
            List.of(),
            metadataOf(
                "planEvaluationValid", false,
                "planEvaluationStage", stage,
                "planEvaluationError", evaluationError
            ),
            0L
        );
    }

    private Map<String, Object> analyzeInterpretationPlanEvidence(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result,
        int iteration,
        List<Map<String, Object>> previousEvidence,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata,
        BooleanSupplier cancellationCheck
    ) {
        runtimeGuard.checkCancelled(cancellationCheck);
        List<Map<String, Object>> toolEvidence = interpretationToolEvidence(plan, result, iteration);
        boolean fallbackSufficient = result != null && result.success();
        Map<String, Object> analysis = new LinkedHashMap<>();
        List<Object> evidenceUsed = new ArrayList<>();
        List<Object> missingEvidence = new ArrayList<>();
        List<Object> conflicts = new ArrayList<>();
        List<Object> nextActions = new ArrayList<>();
        List<String> conclusions = new ArrayList<>();
        List<Map<String, Object>> currentHypotheses = new ArrayList<>();
        boolean explicitIterationDecision = false;
        boolean iterationSufficient = fallbackSufficient;
        for (Map<String, Object> item : toolEvidence) {
            if (Boolean.TRUE.equals(item.get("success"))) {
                evidenceUsed.add(metadataOf(
                    "evidence_id", item.get("evidenceId"),
                    "basis", firstNonBlank(stringValue(item.get("reviewReason")), "successful MCP result")
                ));
            }
            addEvidenceAnalysisItems(missingEvidence, item.get("missingEvidence"));
            addEvidenceAnalysisItems(conflicts, item.get("conflicts"));
            addEvidenceAnalysisItems(nextActions, item.get("nextActions"));
            currentHypotheses.addAll(normalizeHypotheses(
                item.get("hypotheses"),
                stringValue(item.get("evidenceId"))
            ));
            String reason = stringValue(item.get("reviewReason"));
            if (reason != null && !reason.isBlank()) {
                conclusions.add(reason);
            }
            if (item.get("iterationSufficient") != null) {
                explicitIterationDecision = true;
                iterationSufficient &= booleanValue(item.get("iterationSufficient"));
            }
        }
        analysis.put("sufficient", explicitIterationDecision ? iterationSufficient : fallbackSufficient);
        analysis.put("conclusion", conclusions.isEmpty()
            ? (fallbackSufficient ? "The round returned usable evidence." : "The round did not return sufficient evidence.")
            : String.join(" ", conclusions));
        analysis.put("evidence_used", evidenceUsed);
        analysis.put("missing_evidence", missingEvidence);
        analysis.put("conflicts", conflicts);
        analysis.put("next_actions", nextActions);

        List<Map<String, Object>> hypotheses = mergeHypotheses(previousEvidence, currentHypotheses);
        Map<String, Object> evidenceGraph = buildEvidenceGraph(
            iteration,
            previousEvidence,
            toolEvidence,
            hypotheses
        );
        boolean sufficient = booleanValue(firstObject(analysis, "sufficient", "satisfied", "complete"))
            && missingEvidence.isEmpty()
            && conflicts.isEmpty();
        double confidence = evidenceConfidence(toolEvidence, hypotheses);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractVersion", "interpretation_evidence_iteration_v1");
        snapshot.put("iteration", iteration);
        snapshot.put("status", result == null ? "missing" : result.status());
        snapshot.put("executionSuccess", result != null && result.success());
        snapshot.put("sufficient", sufficient);
        snapshot.put("conclusion", firstNonBlank(
            stringValue(firstObject(analysis, "conclusion", "summary", "analysis")),
            result == null ? "No execution result was produced." : firstNonBlank(result.errorMessage(),
                result.success() ? "The round returned usable evidence." : "The round did not return sufficient evidence.")
        ));
        snapshot.put("evidenceUsed", evidenceAnalysisValue(analysis,
            "evidence_used", "evidenceUsed", "basis", "based_on"));
        snapshot.put("missingEvidence", evidenceAnalysisValue(analysis,
            "missing_evidence", "missingEvidence", "missing", "gaps"));
        snapshot.put("conflicts", evidenceAnalysisValue(analysis,
            "conflicts", "contradictions", "uncertainty"));
        snapshot.put("hypotheses", hypotheses);
        snapshot.put("evidenceGraph", evidenceGraph);
        snapshot.put("confidence", confidence);
        snapshot.put("confidenceType", evidenceConfidenceType(toolEvidence, hypotheses));
        snapshot.put("remainingMissing", missingEvidence);
        snapshot.put("nextActions", evidenceAnalysisValue(analysis,
            "next_actions", "nextActions", "next_queries", "nextQueries", "query_revisions", "queryRevisions"));
        snapshot.put("toolEvidence", toolEvidence);
        snapshot.put("createdAt", System.currentTimeMillis());

        addCandidateList(metadataList(metadata, "interpretationPlanEvidenceHistory"), List.of(snapshot));
        metadata.put("interpretationPlanEvidenceIterationCount", iteration);
        metadata.put("interpretationPlanEvidenceSufficient", sufficient);
        metadata.put("interpretationPlanEvidenceConfidence", confidence);
        metadata.put("interpretationPlanRemainingMissing", missingEvidence);
        metadata.put("interpretationPlanEvidenceGraph", evidenceGraph);
        for (Map<String, Object> evidenceObject : toolEvidence) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                AGENT_RUN_ID_ATTRIBUTE,
                "Captured " + firstNonBlank(stringValue(evidenceObject.get("evidenceId")), "MCP evidence") + ".",
                "interpretation_plan_evidence_object",
                metadataOf(
                    "type", "evidence",
                    "workflow", "interpretation_plan",
                    "lifecyclePhase", "evidence_capture",
                    "contractVersion", "evidence_object_v1",
                    "iteration", iteration,
                    "evidenceId", evidenceObject.get("evidenceId"),
                    "evidenceObject", evidenceObject
                )
            );
        }
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            "Evidence iteration " + iteration + " analyzed: "
                + firstNonBlank(stringValue(snapshot.get("conclusion")), "no conclusion"),
            "interpretation_plan_evidence",
            metadataOf(
                "type", "evidence",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "evidence_analysis",
                "contractVersion", "interpretation_evidence_iteration_v1",
                "iteration", iteration,
                "sufficient", sufficient,
                "confidence", confidence,
                "evidenceSnapshot", snapshot
            )
        );
        if (!hypotheses.isEmpty()) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                AGENT_RUN_ID_ATTRIBUTE,
                "Hypothesis state updated for evidence iteration " + iteration + ".",
                "interpretation_plan_hypothesis",
                metadataOf(
                    "type", "hypothesis",
                    "workflow", "interpretation_plan",
                    "lifecyclePhase", "hypothesis_evaluation",
                    "contractVersion", "interpretation_hypothesis_state_v1",
                    "iteration", iteration,
                    "hypotheses", hypotheses
                )
            );
        }
        if (collectionSize(evidenceGraph.get("relations")) > 0
            || collectionSize(evidenceGraph.get("rejectedRelations")) > 0) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                AGENT_RUN_ID_ATTRIBUTE,
                "Evidence graph updated for evidence iteration " + iteration + ".",
                "interpretation_plan_evidence_graph",
                metadataOf(
                    "type", "evidence_graph",
                    "workflow", "interpretation_plan",
                    "lifecyclePhase", "evidence_relationship",
                    "contractVersion", "evidence_graph_v1",
                    "iteration", iteration,
                    "evidenceGraph", evidenceGraph
                )
            );
        }
        return snapshot;
    }

    private void addEvidenceAnalysisItems(List<Object> target, Object value) {
        if (target == null || value == null) {
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    target.add(item);
                }
            }
            return;
        }
        if (!String.valueOf(value).isBlank()) {
            target.add(value);
        }
    }

    private List<Map<String, Object>> interpretationToolEvidence(
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result,
        int iteration
    ) {
        if (result == null || result.steps() == null || result.steps().isEmpty()) {
            return List.of();
        }
        Map<Integer, InterpretationPlan.Step> plannedSteps = new LinkedHashMap<>();
        if (plan != null && plan.steps() != null) {
            for (InterpretationPlan.Step step : plan.steps()) {
                if (step != null && step.id() != null) {
                    plannedSteps.put(step.id(), step);
                }
            }
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            if (step == null || step.stepId() == null || "final_answer".equals(step.actionType())) {
                continue;
            }
            String evidenceId = "iteration:" + iteration + ":step:" + step.stepId()
                + ":tool:" + firstNonBlank(step.toolName(), step.actionType());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("contractVersion", "evidence_object_v1");
            item.put("evidenceId", evidenceId);
            item.put("iteration", iteration);
            item.put("stepId", step.stepId());
            item.put("tool", firstNonBlank(step.toolName(), step.actionType()));
            InterpretationPlan.Step plannedStep = plannedSteps.get(step.stepId());
            item.put("input", plannedStep == null || plannedStep.input() == null ? Map.of() : plannedStep.input());
            item.put("success", step.success());
            item.put("reviewSatisfied", step.metadata() == null ? null
                : step.metadata().get("toolResultReviewSatisfied"));
            item.put("reviewReason", step.metadata() == null ? null
                : step.metadata().get("toolResultReviewReason"));
            item.put("iterationSufficient", step.metadata() == null ? null
                : step.metadata().get("evidenceIterationSufficient"));
            item.put("evidenceBasis", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("evidenceBasis", List.of()));
            item.put("missingEvidence", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("missingEvidence", List.of()));
            item.put("conflicts", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("evidenceConflicts", List.of()));
            item.put("nextActions", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("nextActions", List.of()));
            item.put("hypotheses", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("hypotheses", List.of()));
            if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
                item.put("error", step.errorMessage());
            }
            item.put("output", step.output());
            item.put("outputFacts", structuredOutputFacts(step.output()));
            item.put("outputPreview", shortObservationText(stringify(step.output()), 6000));
            Map<String, Object> evidenceMetadata = new LinkedHashMap<>();
            evidenceMetadata.put("timestamp", System.currentTimeMillis());
            evidenceMetadata.put("source", evidenceSource(step));
            evidenceMetadata.put("confidence", step.metadata() == null
                ? null
                : step.metadata().get("toolResultReviewConfidence"));
            evidenceMetadata.put("success", step.success());
            evidenceMetadata.put("durationMs", step.durationMs());
            evidenceMetadata.put("reviewSatisfied", item.get("reviewSatisfied"));
            evidenceMetadata.put("reviewReason", item.get("reviewReason"));
            item.put("metadata", evidenceMetadata);
            item.put("evidenceQuality", evidenceQuality(step, item, evidenceMetadata));
            item.put("rawResultStored", true);
            evidence.add(item);
        }
        return List.copyOf(evidence);
    }

    private Map<String, Object> evidenceQuality(
        InterpretationPlanRuntime.StepExecution step,
        Map<String, Object> evidence,
        Map<String, Object> evidenceMetadata
    ) {
        Map<String, Object> output = asMap(step == null ? null : step.output());
        Object explicitReliability = firstObject(output,
            "sourceReliability", "source_reliability", "reliability");
        Object explicitFreshness = firstObject(output,
            "freshness", "freshnessScore", "freshness_score");
        Double sourceReliabilityScore = explicitQualityScore(explicitReliability);
        Double freshnessScore = explicitQualityScore(explicitFreshness);
        int missingCount = collectionSize(evidence == null ? null : evidence.get("missingEvidence"));
        int conflictCount = collectionSize(evidence == null ? null : evidence.get("conflicts"));
        boolean hasOutput = step != null && step.output() != null
            && !stringify(step.output()).isBlank()
            && !"{}".equals(stringify(step.output()))
            && !"[]".equals(stringify(step.output()));
        double completeness = step != null && step.success() && hasOutput
            ? clampScore(1.0 / (1.0 + (missingCount * 0.35)))
            : 0.0;
        double consistency = clampScore(1.0 / (1.0 + conflictCount));
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("contractVersion", "evidence_quality_v1");
        quality.put("sourceReliability", sourceReliabilityScore == null
            ? unknownQualityMetric("Source reliability metadata is unavailable.")
            : assessedQualityMetric(sourceReliabilityScore, "COMPUTED",
                "Derived from explicit source reliability metadata returned by the tool."));
        quality.put("freshness", freshnessScore == null
            ? unknownQualityMetric("Freshness metadata is unavailable.")
            : assessedQualityMetric(freshnessScore, "COMPUTED",
                "Derived from explicit freshness metadata returned by the tool."));
        quality.put("completeness", assessedQualityMetric(
            completeness,
            "COMPUTED",
            hasOutput
                ? "Computed from execution success, returned output, and declared evidence gaps."
                : "No usable output was returned."
        ));
        quality.put("consistency", assessedQualityMetric(
            consistency,
            "COMPUTED",
            conflictCount == 0
                ? "No evidence conflict was declared for this result."
                : "Reduced by declared evidence conflicts."
        ));
        Object rawModelConfidence = evidenceMetadata.get("confidence");
        Double modelConfidenceScore = explicitQualityScore(rawModelConfidence);
        quality.put("modelConfidence", modelConfidenceScore == null
            ? unknownQualityMetric("The model did not provide a confidence estimate.", "MODEL_ESTIMATED")
            : assessedQualityMetric(modelConfidenceScore, "MODEL_ESTIMATED",
                "Model-estimated confidence; excluded from evidence quality dimensions."));
        quality.put("aggregatePolicy", "NO_PERSISTED_TOTAL_SCORE");
        quality.put("method", "runtime_structural_quality_v1");
        quality.put("signals", metadataOf(
            "explicitSourceReliability", sourceReliabilityScore != null,
            "explicitFreshness", freshnessScore != null,
            "missingEvidenceCount", missingCount,
            "conflictCount", conflictCount,
            "hasOutput", hasOutput
        ));
        return quality;
    }

    private Double explicitQualityScore(Object value) {
        Object candidate = value;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> metric = asStringObjectMap(raw);
            if ("UNKNOWN".equalsIgnoreCase(stringValue(metric.get("status")))) {
                return null;
            }
            candidate = metric.get("value");
        }
        if (candidate instanceof Number number) {
            return clampScore(number.doubleValue());
        }
        if (candidate == null || String.valueOf(candidate).isBlank()) {
            return null;
        }
        try {
            return clampScore(Double.parseDouble(String.valueOf(candidate).trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> assessedQualityMetric(double value, String type, String reason) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("value", clampScore(value));
        metric.put("status", "ASSESSED");
        metric.put("type", firstNonBlank(type, "COMPUTED"));
        metric.put("reason", firstNonBlank(reason, "Quality dimension was assessed."));
        return metric;
    }

    private Map<String, Object> unknownQualityMetric(String reason) {
        return unknownQualityMetric(reason, "NOT_ASSESSED");
    }

    private Map<String, Object> unknownQualityMetric(String reason, String type) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("value", null);
        metric.put("status", "UNKNOWN");
        metric.put("type", firstNonBlank(type, "NOT_ASSESSED"));
        metric.put("reason", firstNonBlank(reason, "Quality dimension cannot be assessed."));
        return metric;
    }

    private int collectionSize(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return value == null || String.valueOf(value).isBlank() ? 0 : 1;
    }

    private String evidenceSource(InterpretationPlanRuntime.StepExecution step) {
        Map<String, Object> output = asMap(step == null ? null : step.output());
        return firstNonBlank(
            stringValue(firstObject(output, "source", "provider", "index", "indexName", "dataset")),
            step == null ? null : firstNonBlank(step.toolName(), step.actionType())
        );
    }

    private List<Map<String, Object>> normalizeHypotheses(Object raw, String currentEvidenceId) {
        List<?> values;
        if (raw instanceof List<?> list) {
            values = list;
        } else if (raw instanceof Map<?, ?> map) {
            values = List.of(map);
        } else {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> source = asStringObjectMap(map);
            String statement = stringValue(firstObject(source, "statement", "hypothesis", "description"));
            if (statement == null || statement.isBlank()) {
                continue;
            }
            String status = normalizedHypothesisStatus(stringValue(firstObject(source, "status", "state")));
            String hypothesisId = firstNonBlank(
                stringValue(firstObject(source, "hypothesis_id", "hypothesisId", "id")),
                stableHypothesisId(statement)
            );
            List<String> support = new ArrayList<>(stringList(firstObject(source,
                "support_evidence_ids", "supportEvidenceIds", "support")));
            List<String> contradict = new ArrayList<>(stringList(firstObject(source,
                "contradict_evidence_ids", "contradictEvidenceIds", "contradict")));
            if (currentEvidenceId != null && !currentEvidenceId.isBlank()) {
                if ("SUPPORTED".equals(status) && !support.contains(currentEvidenceId)) {
                    support.add(currentEvidenceId);
                } else if ("CONTRADICTED".equals(status) && !contradict.contains(currentEvidenceId)) {
                    contradict.add(currentEvidenceId);
                }
            }
            Map<String, Object> hypothesis = new LinkedHashMap<>();
            hypothesis.put("hypothesisId", hypothesisId);
            hypothesis.put("contractVersion", "hypothesis_tree_v1");
            String parentId = stringValue(firstObject(source,
                "parent_hypothesis_id", "parentHypothesisId", "parentId"));
            hypothesis.put("parentHypothesisId",
                parentId == null || parentId.isBlank() || hypothesisId.equals(parentId) ? null : parentId);
            hypothesis.put("childHypothesisIds", stringList(firstObject(source,
                "child_hypothesis_ids", "childHypothesisIds", "children")));
            hypothesis.put("statement", statement.trim());
            hypothesis.put("supportEvidenceIds", List.copyOf(support));
            hypothesis.put("contradictEvidenceIds", List.copyOf(contradict));
            hypothesis.put("confidence", scoreValue(firstObject(source, "confidence", "score")));
            hypothesis.put("status", status);
            result.add(hypothesis);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> mergeHypotheses(
        List<Map<String, Object>> previousEvidence,
        List<Map<String, Object>> currentHypotheses
    ) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        if (previousEvidence != null) {
            for (Map<String, Object> snapshot : previousEvidence) {
                Object raw = snapshot == null ? null : snapshot.get("hypotheses");
                for (Map<String, Object> hypothesis : normalizeHypotheses(raw, null)) {
                    mergeHypothesis(merged, hypothesis);
                }
            }
        }
        if (currentHypotheses != null) {
            currentHypotheses.forEach(hypothesis -> mergeHypothesis(merged, hypothesis));
        }
        rebuildHypothesisTree(merged);
        return List.copyOf(merged.values());
    }

    private void mergeHypothesis(
        Map<String, Map<String, Object>> target,
        Map<String, Object> incoming
    ) {
        String id = stringValue(incoming == null ? null : incoming.get("hypothesisId"));
        if (id == null || id.isBlank()) {
            return;
        }
        Map<String, Object> existing = target.get(id);
        if (existing == null) {
            target.put(id, new LinkedHashMap<>(incoming));
            return;
        }
        LinkedHashSet<String> support = new LinkedHashSet<>(stringList(existing.get("supportEvidenceIds")));
        support.addAll(stringList(incoming.get("supportEvidenceIds")));
        LinkedHashSet<String> contradict = new LinkedHashSet<>(stringList(existing.get("contradictEvidenceIds")));
        contradict.addAll(stringList(incoming.get("contradictEvidenceIds")));
        existing.put("statement", firstNonBlank(
            stringValue(incoming.get("statement")),
            stringValue(existing.get("statement"))
        ));
        Object incomingParent = incoming.get("parentHypothesisId");
        if (incomingParent != null && !String.valueOf(incomingParent).isBlank() && !id.equals(String.valueOf(incomingParent))) {
            existing.put("parentHypothesisId", incomingParent);
        }
        existing.put("supportEvidenceIds", List.copyOf(support));
        existing.put("contradictEvidenceIds", List.copyOf(contradict));
        existing.put("confidence", incoming.getOrDefault("confidence", existing.getOrDefault("confidence", 0.0)));
        existing.put("status", incoming.getOrDefault("status", existing.getOrDefault("status", "UNRESOLVED")));
    }

    private void rebuildHypothesisTree(Map<String, Map<String, Object>> hypotheses) {
        if (hypotheses == null || hypotheses.isEmpty()) {
            return;
        }
        hypotheses.values().forEach(item -> {
            item.put("contractVersion", "hypothesis_tree_v1");
            item.put("childHypothesisIds", new ArrayList<String>());
        });
        for (Map.Entry<String, Map<String, Object>> entry : hypotheses.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> item = entry.getValue();
            String parentId = stringValue(item.get("parentHypothesisId"));
            if (parentId == null || parentId.isBlank() || id.equals(parentId) || !hypotheses.containsKey(parentId)) {
                item.put("parentHypothesisId", null);
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> children = (List<String>) hypotheses.get(parentId).get("childHypothesisIds");
            if (!children.contains(id)) {
                children.add(id);
            }
        }
        hypotheses.forEach((id, item) -> {
            item.put("childHypothesisIds", List.copyOf(stringList(item.get("childHypothesisIds"))));
            item.put("level", hypothesisLevel(id, hypotheses));
        });
        hypotheses.forEach((id, item) -> {
            List<String> children = stringList(item.get("childHypothesisIds"));
            if (children.isEmpty()) {
                item.put("aggregateStatus", item.getOrDefault("status", "UNRESOLVED"));
                item.put("childStatusCounts", Map.of());
                return;
            }
            Map<String, Long> counts = children.stream()
                .map(hypotheses::get)
                .filter(Objects::nonNull)
                .map(child -> normalizedHypothesisStatus(stringValue(child.get("status"))))
                .collect(java.util.stream.Collectors.groupingBy(
                    status -> status,
                    LinkedHashMap::new,
                    java.util.stream.Collectors.counting()
                ));
            String aggregateStatus;
            if (counts.getOrDefault("UNRESOLVED", 0L) > 0) {
                aggregateStatus = "UNRESOLVED";
            } else if (counts.getOrDefault("SUPPORTED", 0L) > 0) {
                aggregateStatus = "SUPPORTED";
            } else {
                aggregateStatus = "CONTRADICTED";
            }
            item.put("aggregateStatus", aggregateStatus);
            item.put("childStatusCounts", counts);
        });
    }

    private int hypothesisLevel(String hypothesisId, Map<String, Map<String, Object>> hypotheses) {
        int level = 0;
        String current = hypothesisId;
        Set<String> visited = new LinkedHashSet<>();
        boolean cycle = false;
        while (current != null && hypotheses.containsKey(current) && level < 20) {
            if (!visited.add(current)) {
                cycle = true;
                break;
            }
            String parent = stringValue(hypotheses.get(current).get("parentHypothesisId"));
            if (parent == null || parent.isBlank() || !hypotheses.containsKey(parent)) {
                break;
            }
            level++;
            current = parent;
        }
        if (cycle || level >= 20) {
            Map<String, Object> cyclic = hypotheses.get(hypothesisId);
            if (cyclic != null) {
                cyclic.put("parentHypothesisId", null);
            }
            return 0;
        }
        return level;
    }

    private String normalizedHypothesisStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Set.of("SUPPORTED", "CONTRADICTED", "UNRESOLVED").contains(normalized)
            ? normalized
            : "UNRESOLVED";
    }

    private String stableHypothesisId(String statement) {
        String normalized = statement == null ? "" : statement.trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
        return "H-" + Integer.toUnsignedString(normalized.hashCode(), 16).toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> buildEvidenceGraph(
        int iteration,
        List<Map<String, Object>> previousEvidence,
        List<Map<String, Object>> currentEvidence,
        List<Map<String, Object>> hypotheses
    ) {
        LinkedHashMap<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        if (previousEvidence != null) {
            for (Map<String, Object> snapshot : previousEvidence) {
                collectEvidenceGraphNodes(nodes, snapshot == null ? null : snapshot.get("toolEvidence"));
            }
        }
        collectEvidenceGraphNodes(nodes, currentEvidence);
        if (hypotheses != null) {
            for (Map<String, Object> hypothesis : hypotheses) {
                String hypothesisId = stringValue(hypothesis == null ? null : hypothesis.get("hypothesisId"));
                if (hypothesisId == null || hypothesisId.isBlank()) {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("nodeId", hypothesisId);
                node.put("nodeType", "HYPOTHESIS");
                node.put("refId", hypothesisId);
                node.put("status", hypothesis.getOrDefault("status", "UNRESOLVED"));
                node.put("statement", hypothesis.get("statement"));
                nodes.put(hypothesisId, node);
            }
        }

        LinkedHashMap<String, Map<String, Object>> relations = new LinkedHashMap<>();
        List<Map<String, Object>> rejectedRelations = new ArrayList<>();
        if (hypotheses != null) {
            for (Map<String, Object> hypothesis : hypotheses) {
                String hypothesisId = stringValue(hypothesis == null ? null : hypothesis.get("hypothesisId"));
                if (hypothesisId == null || hypothesisId.isBlank()) {
                    continue;
                }
                addEvidenceGraphRelations(
                    relations,
                    rejectedRelations,
                    nodes,
                    stringList(hypothesis.get("supportEvidenceIds")),
                    hypothesisId,
                    "SUPPORTS",
                    iteration
                );
                addEvidenceGraphRelations(
                    relations,
                    rejectedRelations,
                    nodes,
                    stringList(hypothesis.get("contradictEvidenceIds")),
                    hypothesisId,
                    "CONTRADICTS",
                    iteration
                );
                String parentId = stringValue(hypothesis.get("parentHypothesisId"));
                if (parentId != null && !parentId.isBlank() && nodes.containsKey(parentId)) {
                    Map<String, Object> relation = evidenceGraphRelation(
                        parentId,
                        hypothesisId,
                        "DECOMPOSES_TO",
                        iteration
                    );
                    relations.put(stringValue(relation.get("relationId")), relation);
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("contractVersion", "evidence_graph_v1");
        graph.put("graphId", "evidence-graph:iteration:" + iteration);
        graph.put("iteration", iteration);
        graph.put("nodes", List.copyOf(nodes.values()));
        graph.put("relations", List.copyOf(relations.values()));
        graph.put("rejectedRelations", List.copyOf(rejectedRelations));
        graph.put("createdAt", System.currentTimeMillis());
        return graph;
    }

    private void collectEvidenceGraphNodes(
        Map<String, Map<String, Object>> nodes,
        Object rawEvidence
    ) {
        if (nodes == null || !(rawEvidence instanceof Iterable<?> evidenceObjects)) {
            return;
        }
        for (Object value : evidenceObjects) {
            if (!(value instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> evidence = asStringObjectMap(raw);
            String evidenceId = stringValue(evidence.get("evidenceId"));
            if (evidenceId == null || evidenceId.isBlank()) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeId", evidenceId);
            node.put("nodeType", "EVIDENCE");
            node.put("refId", evidenceId);
            node.put("tool", evidence.get("tool"));
            node.put("iteration", evidence.get("iteration"));
            node.put("success", evidence.get("success"));
            nodes.put(evidenceId, node);
        }
    }

    private void addEvidenceGraphRelations(
        Map<String, Map<String, Object>> relations,
        List<Map<String, Object>> rejectedRelations,
        Map<String, Map<String, Object>> nodes,
        List<String> evidenceIds,
        String hypothesisId,
        String relationType,
        int iteration
    ) {
        for (String evidenceId : evidenceIds) {
            if (evidenceId == null || evidenceId.isBlank()) {
                continue;
            }
            if (!nodes.containsKey(evidenceId)) {
                rejectedRelations.add(metadataOf(
                    "from", evidenceId,
                    "to", hypothesisId,
                    "relationType", relationType,
                    "reason", "UNKNOWN_EVIDENCE_REFERENCE"
                ));
                continue;
            }
            Map<String, Object> relation = evidenceGraphRelation(
                evidenceId,
                hypothesisId,
                relationType,
                iteration
            );
            relations.put(stringValue(relation.get("relationId")), relation);
        }
    }

    private Map<String, Object> evidenceGraphRelation(
        String from,
        String to,
        String relationType,
        int iteration
    ) {
        String relationKey = from + "|" + relationType + "|" + to;
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("relationId", "R-"
            + Integer.toUnsignedString(relationKey.hashCode(), 16).toUpperCase(Locale.ROOT));
        relation.put("relationType", relationType);
        relation.put("from", from);
        relation.put("to", to);
        relation.put("iteration", iteration);
        relation.put("status", "ACTIVE");
        return relation;
    }

    private double evidenceConfidence(
        List<Map<String, Object>> toolEvidence,
        List<Map<String, Object>> hypotheses
    ) {
        List<Double> scores = new ArrayList<>();
        if (hypotheses != null) {
            hypotheses.stream()
                .map(item -> scoreValue(item.get("confidence")))
                .filter(score -> score > 0.0)
                .forEach(scores::add);
        }
        if (scores.isEmpty() && toolEvidence != null) {
            for (Map<String, Object> evidence : toolEvidence) {
                Map<String, Object> quality = asMap(evidence.get("evidenceQuality"));
                for (String dimension : List.of(
                    "sourceReliability", "freshness", "completeness", "consistency"
                )) {
                    Double score = assessedQualityMetricValue(quality.get(dimension));
                    if (score != null) {
                        scores.add(score);
                    }
                }
            }
        }
        if (scores.isEmpty()) {
            return 0.0;
        }
        return clampScore(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    private String evidenceConfidenceType(
        List<Map<String, Object>> toolEvidence,
        List<Map<String, Object>> hypotheses
    ) {
        if (hypotheses != null && hypotheses.stream()
            .map(item -> scoreValue(item.get("confidence")))
            .anyMatch(score -> score > 0.0)) {
            return "MODEL_ESTIMATED";
        }
        if (toolEvidence != null) {
            for (Map<String, Object> evidence : toolEvidence) {
                Map<String, Object> quality = asMap(evidence.get("evidenceQuality"));
                for (String dimension : List.of(
                    "sourceReliability", "freshness", "completeness", "consistency"
                )) {
                    if (assessedQualityMetricValue(quality.get(dimension)) != null) {
                        return "EVIDENCE_QUALITY_DERIVED";
                    }
                }
            }
        }
        return "UNKNOWN";
    }

    private Double assessedQualityMetricValue(Object rawMetric) {
        if (!(rawMetric instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> metric = asStringObjectMap(raw);
        if (!"ASSESSED".equalsIgnoreCase(stringValue(metric.get("status")))
            || metric.get("value") == null) {
            return null;
        }
        return scoreValue(metric.get("value"));
    }

    private Object evidenceAnalysisValue(Map<String, Object> analysis, String... keys) {
        Object value = firstObject(analysis, keys);
        return value == null ? List.of() : value;
    }

    private boolean evidenceSufficient(Map<String, Object> snapshot) {
        return snapshot != null && booleanValue(snapshot.get("sufficient"));
    }

    private void recordEvidenceStopState(
        Map<String, Object> metadata,
        Map<String, Object> snapshot,
        String stopReason,
        int iterations
    ) {
        if (metadata == null) {
            return;
        }
        Object remainingMissing = snapshot == null
            ? List.of()
            : snapshot.getOrDefault("remainingMissing", snapshot.getOrDefault("missingEvidence", List.of()));
        metadata.put("stopReason", stopReason);
        metadata.put("evidenceConfidence", snapshot == null ? 0.0 : scoreValue(snapshot.get("confidence")));
        metadata.put("remainingMissing", remainingMissing);
        metadata.put("evidenceIterations", Math.max(0, iterations));
        metadata.put("evidenceStopState", metadataOf(
            "contractVersion", "agent_evidence_stop_v1",
            "stopReason", stopReason,
            "confidence", metadata.get("evidenceConfidence"),
            "remainingMissing", remainingMissing,
            "iterations", Math.max(0, iterations)
        ));
    }

    private String evidenceRewriteReason(
        InterpretationPlanRuntime.ExecutionResult result,
        List<Map<String, Object>> evidenceHistory
    ) {
        Map<String, Object> latest = evidenceHistory == null || evidenceHistory.isEmpty()
            ? Map.of()
            : evidenceHistory.get(evidenceHistory.size() - 1);
        return "EVIDENCE_REFINEMENT_REQUIRED: conclusion="
            + firstNonBlank(stringValue(latest.get("conclusion")), "none")
            + "; missingEvidence=" + latest.getOrDefault("missingEvidence", List.of())
            + "; conflicts=" + latest.getOrDefault("conflicts", List.of())
            + "; previousExecutionError="
            + firstNonBlank(result == null ? null : result.errorMessage(), "none");
    }

    private List<InterpretationPlanRewriter.RequiredToolExecution> evidenceRefinementRequiredTools(
        List<Map<String, Object>> evidenceHistory,
        List<String> availableTools
    ) {
        if (evidenceHistory == null || evidenceHistory.isEmpty()
            || evidenceSufficient(evidenceHistory.get(evidenceHistory.size() - 1))) {
            return List.of();
        }
        Object nextActions = evidenceHistory.get(evidenceHistory.size() - 1).get("nextActions");
        if (!(nextActions instanceof Iterable<?> actions)) {
            return List.of();
        }
        List<InterpretationPlanRewriter.RequiredToolExecution> required = new ArrayList<>();
        for (Object action : actions) {
            if (!(action instanceof Map<?, ?> actionMap)) {
                continue;
            }
            String requestedTool = stringValue(firstObject(asStringObjectMap(actionMap),
                "tool", "toolName", "tool_name"));
            String availableTool = matchingAvailableTool(requestedTool, availableTools);
            if (availableTool == null) {
                continue;
            }
            boolean alreadyAdded = required.stream().anyMatch(item -> toolNames.sameToolName(
                item.toolName(), availableTool));
            if (!alreadyAdded) {
                required.add(new InterpretationPlanRewriter.RequiredToolExecution(
                    availableTool, "EVIDENCE_REFINEMENT", true
                ));
            }
        }
        return List.copyOf(required);
    }

    private Map<String, Object> asStringObjectMap(Map<?, ?> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }
        return values;
    }

    private String matchingAvailableTool(String requestedTool, List<String> availableTools) {
        if (requestedTool == null || requestedTool.isBlank() || availableTools == null) {
            return null;
        }
        for (String availableTool : availableTools) {
            if (toolNames.sameToolName(requestedTool, availableTool)) {
                return availableTool;
            }
        }
        return null;
    }

    private void recordPlanEvolution(
        InterpretationPlan previousPlan,
        InterpretationPlan nextPlan,
        int iteration,
        String status,
        List<Map<String, Object>> evidenceHistory,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        if (nextPlan == null) {
            return;
        }
        Map<String, Object> trigger = evidenceHistory == null || evidenceHistory.isEmpty()
            ? Map.of()
            : evidenceHistory.get(evidenceHistory.size() - 1);
        List<Map<String, Object>> changes = planChanges(previousPlan, nextPlan);
        Map<String, Object> evolution = new LinkedHashMap<>();
        evolution.put("contractVersion", "plan_evolution_v1");
        evolution.put("evolutionId", firstNonBlank(
            stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)),
            "agent-run"
        ) + ":plan-evolution:" + iteration);
        evolution.put("fromIteration", Math.max(1, iteration - 1));
        evolution.put("toIteration", iteration);
        evolution.put("status", firstNonBlank(status, "UNKNOWN"));
        evolution.put("trigger", metadataOf(
            "conclusion", trigger.get("conclusion"),
            "missingEvidence", trigger.getOrDefault("missingEvidence", List.of()),
            "conflicts", trigger.getOrDefault("conflicts", List.of()),
            "nextActions", trigger.getOrDefault("nextActions", List.of()),
            "evidenceIds", evidenceIdsFromSnapshot(trigger),
            "hypothesisIds", hypothesisIdsFromSnapshot(trigger),
            "evidenceRelationIds", evidenceRelationIdsFromSnapshot(trigger)
        ));
        evolution.put("changes", changes);
        evolution.put("changed", !changes.isEmpty());
        evolution.put("previousPlan", previousPlan);
        evolution.put("nextPlan", nextPlan);
        evolution.put("createdAt", System.currentTimeMillis());
        addCandidateList(metadataList(metadata, "interpretationPlanEvolutionHistory"), List.of(evolution));
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            "Plan evolution " + Math.max(1, iteration - 1) + " -> " + iteration
                + " recorded with " + changes.size() + " change(s).",
            "interpretation_plan_evolution",
            metadataOf(
                "type", "plan",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "plan_revision",
                "contractVersion", "plan_evolution_v1",
                "iteration", iteration,
                "planEvolution", evolution
            )
        );
    }

    private List<Map<String, Object>> planChanges(InterpretationPlan previousPlan, InterpretationPlan nextPlan) {
        Map<Integer, InterpretationPlan.Step> previous = planStepsById(previousPlan);
        Map<Integer, InterpretationPlan.Step> next = planStepsById(nextPlan);
        LinkedHashSet<Integer> stepIds = new LinkedHashSet<>(previous.keySet());
        stepIds.addAll(next.keySet());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Integer stepId : stepIds) {
            InterpretationPlan.Step before = previous.get(stepId);
            InterpretationPlan.Step after = next.get(stepId);
            if (before == null) {
                changes.add(metadataOf("changeType", "STEP_ADDED", "stepId", stepId, "after", after));
            } else if (after == null) {
                changes.add(metadataOf("changeType", "STEP_REMOVED", "stepId", stepId, "before", before));
            } else if (!Objects.equals(before, after)) {
                List<String> fields = new ArrayList<>();
                if (!Objects.equals(before.actionType(), after.actionType())) fields.add("actionType");
                if (!Objects.equals(before.toolName(), after.toolName())) fields.add("toolName");
                if (!Objects.equals(before.input(), after.input())) fields.add("input");
                if (!Objects.equals(before.dependsOn(), after.dependsOn())) fields.add("dependsOn");
                if (!Objects.equals(before.outputContract(), after.outputContract())) fields.add("outputContract");
                if (!Objects.equals(before.validation(), after.validation())) fields.add("validation");
                changes.add(metadataOf(
                    "changeType", "STEP_MODIFIED",
                    "stepId", stepId,
                    "fields", fields,
                    "before", before,
                    "after", after
                ));
            }
        }
        if (previousPlan != null && !Objects.equals(previousPlan.executionPolicy(), nextPlan.executionPolicy())) {
            changes.add(metadataOf(
                "changeType", "EXECUTION_POLICY_MODIFIED",
                "before", previousPlan.executionPolicy(),
                "after", nextPlan.executionPolicy()
            ));
        }
        if (previousPlan != null && !Objects.equals(previousPlan.intent(), nextPlan.intent())) {
            changes.add(metadataOf(
                "changeType", "INTENT_MODIFIED",
                "before", previousPlan.intent(),
                "after", nextPlan.intent()
            ));
        }
        return List.copyOf(changes);
    }

    private Map<Integer, InterpretationPlan.Step> planStepsById(InterpretationPlan plan) {
        Map<Integer, InterpretationPlan.Step> steps = new LinkedHashMap<>();
        if (plan != null && plan.steps() != null) {
            for (InterpretationPlan.Step step : plan.steps()) {
                if (step != null && step.id() != null) {
                    steps.put(step.id(), step);
                }
            }
        }
        return steps;
    }

    private List<String> evidenceIdsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("toolEvidence") instanceof Iterable<?> evidence)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : evidence) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(asStringObjectMap(map).get("evidenceId"));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private List<String> hypothesisIdsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("hypotheses") instanceof Iterable<?> hypotheses)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : hypotheses) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(asStringObjectMap(map).get("hypothesisId"));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private List<String> evidenceRelationIdsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        Map<String, Object> graph = asMap(snapshot.get("evidenceGraph"));
        if (!(graph.get("relations") instanceof Iterable<?> relations)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : relations) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(asStringObjectMap(map).get("relationId"));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private String planAttemptRewriteSummary(
        int nextAttempt,
        InterpretationPlan previousPlan,
        InterpretationPlanRuntime.ExecutionResult previousResult
    ) {
        List<String> failedSteps = new ArrayList<>();
        if (previousResult != null && previousResult.steps() != null) {
            for (InterpretationPlanRuntime.StepExecution step : previousResult.steps()) {
                if (step != null && !step.success()) {
                    failedSteps.add("step " + step.stepId()
                        + " (" + firstNonBlank(step.toolName(), firstNonBlank(step.actionType(), "unknown")) + "): "
                        + firstNonBlank(step.errorMessage(), "result did not satisfy review"));
                }
            }
        }
        String goal = previousPlan == null || previousPlan.intent() == null
            ? null
            : previousPlan.intent().goal();
        return "Plan attempt " + nextAttempt + " rewrite context summary: previous goal="
            + firstNonBlank(goal, "unspecified")
            + "; previous status=" + (previousResult == null ? "unknown" : previousResult.status())
            + "; previous error=" + firstNonBlank(
                previousResult == null ? null : previousResult.errorMessage(),
                "none"
            )
            + "; failed steps=" + failedSteps
            + ". Generate a new complete plan from this evidence, evaluate it, then execute it only if evaluation passes.";
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
            String templateSelectionFeedback = templateSelectionFeedbackObservation(stage, step);
            if (templateSelectionFeedback != null) {
                observations.add(templateSelectionFeedback);
                record.put("templateSelectionFeedbackObservation", true);
            }
            String evidenceEvaluationObservation = evidenceEvaluationObservation(step);
            if (evidenceEvaluationObservation != null && !evidenceEvaluationObservation.isBlank()) {
                observations.add(evidenceEvaluationObservation);
            }
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

    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
            .filter(item -> item != null && !String.valueOf(item).isBlank())
            .map(String::valueOf)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private InterpretationPlanRuntime.ExecutionResult blockIncompleteWorkflow(
        String stage,
        InterpretationPlanRuntime.ExecutionResult result,
        InterpretationPlanWorkflowGuard.GuardResult guard,
        List<String> observations,
        Map<String, Object> metadata
    ) {
        Map<String, Object> guardMetadata = guard == null || guard.metadata() == null ? Map.of() : guard.metadata();
        metadata.put("interpretationPlanWorkflowBlocked", true);
        metadata.put("interpretationPlanWorkflowBlockedStage", stage);
        metadata.put("interpretationPlanWorkflowGuard", guardMetadata);
        metadata.put("interpretationPlanWorkflowMissingTools", guard == null ? List.of() : guard.missingRequiredTools());
        metadata.put("interpretationPlanWorkflowMissingPlanStepIds", guard == null ? List.of() : guard.missingPlanStepIds());
        observations.add("InterpretationPlan final answer blocked: configured MCP workflow must complete before final answer. Missing tools: "
            + (guard == null ? List.of() : guard.missingRequiredTools())
            + ", missing plan steps: "
            + (guard == null ? List.of() : guard.missingPlanStepIds()));
        Map<String, Object> resultMetadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        resultMetadata.put("workflowGuard", guardMetadata);
        return new InterpretationPlanRuntime.ExecutionResult(
            "MCP_WORKFLOW_INCOMPLETE",
            false,
            false,
            guard == null || guard.reason() == null || guard.reason().isBlank()
                ? "Configured MCP workflow is incomplete"
                : guard.reason(),
            result.finalAnswer(),
            result.steps(),
            resultMetadata,
            result.durationMs()
        );
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

    private String templateSelectionFeedbackObservation(String stage,
                                                        InterpretationPlanRuntime.StepExecution step) {
        if (step == null || step.metadata() == null || step.metadata().isEmpty()) {
            return null;
        }
        Map<String, Object> feedback = new LinkedHashMap<>();
        for (String key : List.of("selectedTemplateIds", "rejectedTemplateIds", "refinedIntent")) {
            Object value = step.metadata().get(key);
            if (value != null && !String.valueOf(value).isBlank() && !List.of().equals(value)) {
                feedback.put(key, value);
            }
        }
        if (feedback.isEmpty()) {
            return null;
        }
        feedback.put("schemaVersion", "template_selection_feedback.v1");
        feedback.put("stage", stage);
        feedback.put("stepId", step.stepId());
        feedback.put("toolName", step.toolName());
        return "InterpretationPlan template selection feedback: " + stringify(feedback);
    }

    private String canonicalEvidenceObservation(InterpretationPlanRuntime.StepExecution step) {
        if (step == null || !step.success() || step.toolExecution() == null || step.toolExecution().output() == null) {
            return null;
        }
        String observation = toolObservationBuilder.buildSuccessObservation(
            step.toolName(),
            step.toolExecution().output(),
            stringify(step.output()),
            step.metadata()
        );
        return hasCanonicalEvidence(observation) ? observation : null;
    }

    private String evidenceEvaluationObservation(InterpretationPlanRuntime.StepExecution step) {
        if (step == null || step.metadata() == null || step.metadata().isEmpty()) {
            return null;
        }
        Object evaluation = step.metadata().get("evidenceEvaluation");
        if (!(evaluation instanceof Map<?, ?> evaluationMap) || evaluationMap.isEmpty()) {
            return null;
        }
        return "Evidence evaluation (contractVersion=evidence_evaluation_contract_v1): "
            + shortObservationText(stringify(evaluationMap), 1600);
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
            List.of(),
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
                                              List<InteractionToolTrace> priorTraces,
                                              Map<String, Object> runtimeAttributes) {
        Map<String, Object> safeArguments = new LinkedHashMap<>(
            toolArguments.applyObservedTemplateContract(toolName, arguments, priorTraces));
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
            String failedMandatoryTool = failedMandatoryWorkflowTool(mandatoryTools, traces);
            if (failedMandatoryTool != null) {
                metadata.put("mandatoryWorkflowStoppedOnFailure", failedMandatoryTool);
                observations.add("Mandatory workflow fallback stopped because required tool "
                    + failedMandatoryTool + " already produced a failure observation.");
                return;
            }
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
                traces,
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

    private String failedMandatoryWorkflowTool(List<String> mandatoryTools, List<InteractionToolTrace> traces) {
        if (mandatoryTools == null || mandatoryTools.isEmpty() || traces == null || traces.isEmpty()) {
            return null;
        }
        for (InteractionToolTrace trace : traces) {
            if (trace == null || trace.isSuccess() || trace.getToolName() == null || trace.getToolName().isBlank()) {
                continue;
            }
            Object outcome = trace.getRuntimeMetadata() == null ? null : trace.getRuntimeMetadata().get("outcome");
            if ("confirmation_required".equalsIgnoreCase(String.valueOf(outcome))) {
                continue;
            }
            for (String mandatoryTool : mandatoryTools) {
                if (toolNames.sameToolName(mandatoryTool, trace.getToolName())) {
                    return trace.getToolName();
                }
            }
        }
        return null;
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
                traces,
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
        return ModelProtocolJson.compact(data);
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

    private static final class Counter {
        private int value;
    }

    public record AgentExecutionResult(
        String answer,
        List<InteractionToolTrace> toolTraces,
        Map<String, Object> metadata
    ) {
    }
}
