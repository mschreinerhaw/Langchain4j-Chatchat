package com.chatchat.agents.orchestration;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.assessment.RuntimeAnswerCandidate;
import com.chatchat.agents.assessment.TaskContract;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.agents.runtime.AnswerCandidateCollector;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentObservationPipeline;
import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunExecutor;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRunStore;
import com.chatchat.agents.runtime.AgentRuntimeFactGroundingContract;
import com.chatchat.agents.runtime.AgentRuntimeProperties;
import com.chatchat.agents.runtime.DefaultAgentAnswerReviewer;
import com.chatchat.agents.runtime.DefaultAgentObservationPipeline;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.GovernanceIsolationScope;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.plan.InterpretationPlanDagConverter;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.DiagnosticRun;
import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;
import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;
import com.chatchat.agents.runtime.plan.InterpretationPlanRewriter;
import com.chatchat.agents.runtime.plan.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.agents.runtime.plan.NodeAttemptStore;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.runtime.plan.RetrievalQualityGate;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent orchestrator with tool planning and execution loop.
 */
@Slf4j
@Service
public class AgentOrchestrator implements AgentRunExecutor {

    private static final int DEFAULT_MAX_STEPS = 3;
    private static final int MAX_INTERPRETATION_PLAN_ATTEMPTS = 3;
    private static final int WEB_SEARCH_REFERENCE_LIMIT = 10;
    private static final int DAG_DECISION_OUTPUT_SUMMARY_CHARS = 64_000;
    private static final int DAG_DECISION_EVIDENCE_TOKEN_BUDGET = 12_000;
    private static final int SUMMARY_OBSERVATION_METADATA_CHARS = 16_000;
    private static final int SUMMARY_EVIDENCE_TOKEN_BUDGET = 24_000;
    private static final int SUMMARY_COMPRESSED_OBSERVATION_CHARS = 4_000;
    private static final int RECORD_ANALYSIS_CHUNK_MAX_CHARS = 12_000;
    private static final int RECORD_ANALYSIS_CHUNK_MAX_ROWS = 50;
    private static final Pattern TOOL_OUTPUT_DOCUMENT_ID = Pattern.compile(
        "tool-output:[A-Za-z0-9._:-]+"
    );
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
        DEFAULT_MAX_STEPS,
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
    private final ModelAssistedRetrievalBridge modelAssistedRetrievalBridge;
    private final ModelAssistedContextParameterBridge modelAssistedContextParameterBridge;
    private final AnswerCandidateCollector answerCandidateCollector = new AnswerCandidateCollector();
    private final AgentWorkflowStateTracker workflowStateTracker = new AgentWorkflowStateTracker();
    private final AgentAnswerFinalizer answerFinalizer;
    private final InterpretationPlanStore interpretationPlanStore;
    private final InterpretationPlanDagConverter interpretationPlanDagConverter = new InterpretationPlanDagConverter();
    private final InterpretationPlanWorkflowGuard interpretationPlanWorkflowGuard = new InterpretationPlanWorkflowGuard();
    private final EvidenceAugmentationPolicy evidenceAugmentationPolicy = new EvidenceAugmentationPolicy();
    private final AgentContextBudget contextBudget;
    private final ContextTokenEstimator contextTokenEstimator = new ContextTokenEstimator();
    private final ContextEvidenceAggregator contextEvidenceAggregator = new ContextEvidenceAggregator();
    private final AnalysisSummaryGovernanceBridge analysisSummaryGovernanceBridge =
        new AnalysisSummaryGovernanceBridge();
    private final McpAnalysisContextAdapter mcpAnalysisContextAdapter;
    private DagGovernanceContractProvider dagGovernanceContractProvider =
        DagGovernanceContractProvider.builtInFallback();
    private NodeAttemptStore nodeAttemptStore;

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
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, observationPipeline, answerReviewer,
            interpretationPlanStore, new AgentRuntimeProperties());
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
                             InterpretationPlanStore interpretationPlanStore,
                             AgentRuntimeProperties agentRuntimeProperties) {
        this.toolRegistry = toolRegistry;
        this.toolRuntimeService = toolRuntimeService;
        this.objectMapper = objectMapper;
        this.mcpAnalysisContextAdapter = new McpAnalysisContextAdapter(objectMapper);
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
        this.modelAssistedRetrievalBridge = new ModelAssistedRetrievalBridge(this.toolRegistry, objectMapper);
        this.modelAssistedContextParameterBridge =
            new ModelAssistedContextParameterBridge(this.toolRegistry, objectMapper);
        this.answerFinalizer = new AgentAnswerFinalizer(
            resolvedAnswerReviewer,
            this.runtimeGuard,
            modelsConfig,
            toolRegistry,
            toolRuntimeService,
            objectMapper,
            agentRuntimeProperties
        );
        ModelsConfig resolvedModelsConfig = modelsConfig == null ? new ModelsConfig() : modelsConfig;
        this.contextBudget = new AgentContextBudget(
            Math.max(32_000, resolvedModelsConfig.getContextWindowMaxTokens()),
            Math.max(0, resolvedModelsConfig.getContextReservedSystemTokens()),
            Math.max(0, resolvedModelsConfig.getContextReservedHistoryTokens()),
            Math.max(0, resolvedModelsConfig.getContextReservedOutputTokens())
        );
        this.interpretationPlanStore = interpretationPlanStore == null && this.runStore instanceof InterpretationPlanStore store
            ? store
            : interpretationPlanStore;
    }

    /** Production supplies the database-backed provider; direct unit construction retains a deterministic fallback. */
    @Autowired(required = false)
    public void setDagGovernanceContractProvider(DagGovernanceContractProvider provider) {
        if (provider != null) {
            this.dagGovernanceContractProvider = provider;
        }
    }

    /** Production supplies the database-backed node attempt journal. */
    @Autowired(required = false)
    public void setNodeAttemptStore(NodeAttemptStore nodeAttemptStore) {
        this.nodeAttemptStore = nodeAttemptStore;
    }

    /**
     * Executes an agent run through the stable runtime request/result contract.
     *
     * @param request the agent run request
     * @return the agent run result
     */
    @Override
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
            log.error("Agent orchestration failed. runId={} requestId={} errorType={} error={}",
                run.runId(), request.getRequestId(), ex.getClass().getName(), ex.getMessage(), ex);
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
        pinDagGovernanceContract(attributes);
        return runtimeGuard.attributesWithDeadline(attributes);
    }

    private void pinDagGovernanceContract(Map<String, Object> attributes) {
        if (attributes == null || attributes.containsKey(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE)) {
            return;
        }
        DagGovernanceContractProvider.ContractSnapshot contract =
            dagGovernanceContractProvider.activeContract();
        attributes.put(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE, contract.toRuntimeAttribute());
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
        List<Map<String, Object>> authoritativeWorkflowDag = workflowMandatoryResolution.authoritativeDag().stream()
            .map(node -> metadataOf(
                "id", node.id(),
                "tool", node.toolName(),
                "dependsOnTools", node.dependsOnTools(),
                "order", node.order(),
                "sourceIndex", node.sourceIndex()
            ))
            .toList();
        String authoritativeWorkflowTaskId = firstNonBlank(
            stringValue(requestRuntimeAttributes.get("__agentTaskId")),
            firstNonBlank(stringValue(requestRuntimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)), requestId)
        );
        if (!authoritativeWorkflowDag.isEmpty()) {
            requestRuntimeAttributes.put("authoritativeWorkflowDag", authoritativeWorkflowDag);
            requestRuntimeAttributes.put("authoritativeWorkflowTaskId", authoritativeWorkflowTaskId);
            requestRuntimeAttributes.put("authoritativeWorkflowSource", "user_defined_mcp_workflow");
        }
        List<String> workflowMandatoryTools = workflowMandatoryResolution.tools();
        List<String> mandatoryTools = workflowMandatoryTools.isEmpty()
            ? workflowTools.resolveMandatoryToolCandidates(tools, requiredToolNames)
            : workflowMandatoryTools;
        if (requireDocumentWebVerification) {
            mandatoryTools = workflowTools.withDocumentWebVerificationMandatoryTools(mandatoryTools, documentSearchTool, verificationWebSearchTool);
        }
        if (!mandatoryTools.isEmpty() && !runtimeGuard.hasConfiguredMaxSteps(requestRuntimeAttributes)) {
            maxSteps = Math.max(maxSteps, mandatoryTools.size() + 1);
        }
        boolean requireToolBeforeFinal = !mandatoryTools.isEmpty();
        List<Map<String, Object>> requiredToolExecutionContracts = requiredToolExecutionContracts(
            mandatoryTools,
            requiredToolNames,
            workflowMandatoryTools
        );
        ChatModel activeChatModel = chatModelResolver.resolveChatModel(modelName);
        requestRuntimeAttributes.putIfAbsent("checkpointModelConfig",
            chatModelResolver.checkpointModelConfiguration(modelName, activeChatModel));
        List<InteractionToolTrace> traces = new ArrayList<>();
        List<String> observations = runResultAdapter.runtimeObservationList(stringValue(requestRuntimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Map<String, Object>> plannerSteps = new ArrayList<>();
        metadata.put("agentRunId", stringValue(requestRuntimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)));
        metadata.put("requestId", requestId);
        metadata.put("conversationId", conversationId);
        metadata.put("tenantId", tenantId);
        metadata.put("userId", userId);
        metadata.put("skillId", skillId == null ? "general" : skillId);
        metadata.put("modelName", normalizeModelName(modelName));
        metadata.put("requiredToolParameters",
            requestRuntimeAttributes.getOrDefault("requiredToolParameters", Map.of()));
        metadata.put("boundDocumentIds", documentIds);
        metadata.put("boundDocumentTags", documentTags);
        metadata.put("availableTools", tools);
        metadata.put("webSearchResultLimit", webSearchResultLimit);
        metadata.put("mandatoryToolCall", requireToolBeforeFinal);
        metadata.put("mandatoryTools", mandatoryTools);
        metadata.put("workflowMandatoryTools", workflowMandatoryTools);
        metadata.put("authoritativeWorkflowDag", authoritativeWorkflowDag);
        metadata.put("authoritativeWorkflowTaskId", authoritativeWorkflowTaskId);
        metadata.put("authoritativeWorkflowSource", authoritativeWorkflowDag.isEmpty()
            ? "none" : "user_defined_mcp_workflow");
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
            PlannerExecutionResult plannerResult = planner.decideNextAction(
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
            AgentDecision decision = plannerResult.decision();
            metadata.put("taskContract", plannerResult.taskContract());
            metadata.put("taskContractVersion", plannerResult.taskContract().contractVersion());
            metadata.put("taskType", plannerResult.taskContract().taskType());
            metadata.put("evidenceRequirement",
                plannerResult.taskContract().evidenceRequirement().name());
            if (decision.executionPlan() != null
                && decision.executionPlan().get("artifactContract") instanceof Map<?, ?> artifactContract) {
                metadata.put("artifactContract", new LinkedHashMap<>(artifactContract));
            }
            RuntimeAnswerCandidate plannerCandidate = plannerResult.candidateAnswer();
            if (plannerCandidate != null) {
                plannerCandidate = plannerCandidate.transition(
                    RuntimeAnswerCandidate.Status.VALIDATED,
                    metadataOf(
                        "contentPresent", !plannerCandidate.content().isBlank(),
                        "planValidityIndependent", true
                    )
                );
                metadata.put("answerCandidate", plannerCandidate);
                metadata.put("answerCandidateContractVersion", plannerCandidate.contractVersion());
                metadata.put("answerOrigin", plannerCandidate.source());
                metadata.put("answerGenerationType", plannerCandidate.type());
                metadata.put("answerLifecycleStatus", plannerCandidate.status().name());
                metadata.put("protectedCandidateAnswer", true);
                metadata.put("plannerCandidateAnswerPreserved", true);
            }
            boolean protectedPlannerReport = plannerCandidate != null
                && decision.executionPlan() != null
                && Boolean.TRUE.equals(decision.executionPlan().get("plannerCandidateAnswerPreserved"));
            try {
                runtimeGuard.checkCancelled(cancellationCheck);
            } catch (CancellationException cancellation) {
                boolean mandatoryEvidenceComplete = plannerMandatoryTools.isEmpty();
                boolean verificationComplete = !requireDocumentWebVerification
                    || !workflowTools.missingDocumentWebVerification(
                        plannerCompletedTools, documentSearchTool, verificationWebSearchTool);
                if (FINAL.equals(decision.action())
                    && protectedPlannerReport
                    && mandatoryEvidenceComplete
                    && verificationComplete) {
                    RuntimeAnswerCandidate selectedCandidate = plannerCandidate.transition(
                        RuntimeAnswerCandidate.Status.SELECTED,
                        metadataOf("selectionReason", "displayable_report_completed_at_deadline")
                    );
                    metadata.put("answerCandidate", selectedCandidate);
                    metadata.put("answerLifecycleStatus", selectedCandidate.status().name());
                    return answerFinalizer.finishProducedAnswerAfterCancellation(
                        query,
                        traces,
                        metadata,
                        observations,
                        plannerCandidate.content(),
                        cancellation.getMessage()
                    );
                }
                throw cancellation;
            }
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
            recordPlannerDagRepairEvent(
                requestRuntimeAttributes, metadata,
                decision.executionPlan() == null ? null : decision.executionPlan().get("repairEvent"));
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
                    "plannerProtocol", decision.executionPlan() == null ? null : decision.executionPlan().get("plannerProtocol"),
                    "eventKind", decision.interpretationPlan() == null ? null : "DAG_VALIDATION",
                    "eventState", decision.interpretationPlan() == null ? null
                        : (decision.executionPlan() != null
                            && Boolean.TRUE.equals(decision.executionPlan().get("interpretationPlanValid")) ? "PASSED" : "FAILED")
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
                if (plannerCandidate != null) {
                    RuntimeAnswerCandidate selectedCandidate = plannerCandidate.transition(
                        RuntimeAnswerCandidate.Status.SELECTED,
                        metadataOf("selectionReason", "planner_business_result_retained")
                    );
                    metadata.put("answerCandidate", selectedCandidate);
                    metadata.put("answerLifecycleStatus", selectedCandidate.status().name());
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
                activeChatModel,
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
        runtimeAttributes = interpretationPlanInitialAttributes(runtimeAttributes, traces);
        AgentPlanBudgetPolicy.BudgetCaps budgetCaps = AgentPlanBudgetPolicy.fromRuntimeAttributes(runtimeAttributes);
        AgentPlanBudgetPolicy.ApplyResult budgetResult = AgentPlanBudgetPolicy.apply(plan, budgetCaps);
        plan = budgetResult.plan();
        Object authoritativeWorkflowDag = runtimeAttributes == null
            ? null : runtimeAttributes.get("authoritativeWorkflowDag");
        String authoritativeWorkflowTaskId = runtimeAttributes == null
            ? null : stringValue(runtimeAttributes.get("authoritativeWorkflowTaskId"));
        boolean hasAuthoritativeWorkflowDag = authoritativeWorkflowDag instanceof Collection<?> collection
            && !collection.isEmpty();
        List<String> authoritativeWorkflowDagPasses = List.of();
        if (hasAuthoritativeWorkflowDag) {
            InterpretationPlanOptimizer.OptimizationResult workflowDagOptimization =
                new InterpretationPlanOptimizer().optimize(plan, authoritativeWorkflowDag);
            plan = workflowDagOptimization.plan() == null ? plan : workflowDagOptimization.plan();
            authoritativeWorkflowDagPasses = workflowDagOptimization.appliedPasses();
        }
        metadata.put("interpretationPlanPipeline", true);
        metadata.put("interpretationPlanVersion", plan.version());
        metadata.put("authoritativeWorkflowDagPasses", authoritativeWorkflowDagPasses);
        if (budgetCaps.configured()) {
            metadata.put("agentBudgetCaps", budgetCaps.metadata());
            metadata.put("agentBudgetAdjusted", budgetResult.adjusted());
        }
        saveInterpretationPlanSnapshot("initial", plan, tenantId, requestId, runtimeAttributes, metadata);
        recordPlanEvolution(null, plan, 1, "INITIAL", List.of(), runtimeAttributes, metadata);

        InterpretationPlanValidator validator = new InterpretationPlanValidator();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            validator,
            runStore,
            request -> reviewInterpretationPlanToolResult(activeChatModel, query, systemPrompt, cancellationCheck, request),
            request -> decideInterpretationPlanDagStep(activeChatModel, query, systemPrompt, cancellationCheck, request),
            request -> {
                String stepTool = request.step() == null ? null : request.step().toolName();
                ModelAssistedRetrievalBridge.RetrievalEvidenceContext evidenceContext =
                    templateRetrievalEvidenceContext(query, request.completed());
                Map<String, Object> contextual = modelAssistedContextParameterBridge.propose(
                    activeChatModel, stepTool, request.input(), evidenceContext);
                return modelAssistedRetrievalBridge.enrichWithGate(
                    activeChatModel, stepTool, contextual, evidenceContext).argumentsWithGateMarker();
            }
        );
        runtime.setNodeAttemptStore(nodeAttemptStore);
        List<InterpretationPlanRuntime.ExecutionResult> planAttemptResults = new ArrayList<>();
        List<Map<String, Object>> evidenceHistory = new ArrayList<>();
        InterpretationPlanValidator.ValidationResult initialEvaluation = validator.validate(
            plan,
            toolRegistry,
            new LinkedHashSet<>(tools == null ? List.of() : tools),
            authoritativeWorkflowDag,
            authoritativeWorkflowTaskId
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
        checkCancelledUnlessBatchEvidence(cancellationCheck, firstResult, metadata);

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
        int configuredMaxRewriteTimes = maxRewriteTimes(plan);
        boolean firstEvidenceAvailable = usableEvidenceAvailable(firstEvidence);
        boolean augmentationOverrideAvailable = configuredMaxRewriteTimes == 0
            && firstEvidenceAvailable
            && MAX_INTERPRETATION_PLAN_ATTEMPTS > 1;
        EvidenceAugmentationPolicy.Outcome latestAugmentationDecision = decideEvidenceAugmentation(
            firstEvidence,
            firstResult,
            evidenceExplorationAvailable(
                firstEvidence,
                firstResult,
                tools,
                configuredMaxRewriteTimes > 0 || augmentationOverrideAvailable
            ),
            false,
            metadata
        );
        recordEvidenceAugmentationDecision(
            latestAugmentationDecision, 1, runtimeAttributes, metadata);
        if (latestAugmentationDecision.decision() == EvidenceAugmentationPolicy.Decision.COMPLETE) {
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
            return finishSynthesizedInterpretationPlanAnswer(
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
        Map<Integer, InterpretationPlanRuntime.ReusableStep> reusablePlanSteps =
            reusablePlanSteps(Map.of(), currentPlan, currentResult);
        boolean executionRecoveryRequired = !firstResult.success();
        int maxRewriteTimes = latestAugmentationDecision.continueLoop()
            ? (augmentationOverrideAvailable ? 1 : configuredMaxRewriteTimes)
            : executionRecoveryRequired ? configuredMaxRewriteTimes : 0;
        int evidenceDrivenRewriteLimit = evidenceDrivenRewriteLimit(
            configuredMaxRewriteTimes,
            latestAugmentationDecision,
            evidenceHistory,
            tools
        );
        if (evidenceDrivenRewriteLimit > maxRewriteTimes) {
            maxRewriteTimes = evidenceDrivenRewriteLimit;
            metadata.put("evidenceDrivenRewriteBudgetApplied", true);
            metadata.put("evidenceDrivenRewriteBudgetReason",
                "The evidence chain contains an available-tool next action, so refinement remains enabled within the runtime attempt ceiling.");
        }
        boolean templateExecutionRetryRequested = templateExecutionRetryRequested(firstResult);
        if (templateExecutionRetryRequested && tools != null && !tools.isEmpty()) {
            maxRewriteTimes = Math.max(maxRewriteTimes, 1);
            metadata.put("templateExecutionRetryBounded", true);
            metadata.put("templateExecutionRetryLimit", 1);
            metadata.put("templateExecutionRetryStrategy",
                "EVIDENCE_BASED_PARAMETER_REPAIR_OR_TEMPLATE_RESELECTION");
        }
        boolean duplicateToolPlanSuppressed = false;
        boolean usablePartialAnalysis = latestAugmentationDecision.decision()
            == EvidenceAugmentationPolicy.Decision.ANALYZE_WITH_LIMITATIONS
            && firstEvidenceAvailable;
        metadata.put("interpretationPlanConfiguredMaxRewriteTimes", configuredMaxRewriteTimes);
        metadata.put("interpretationPlanMaxRewriteTimes", maxRewriteTimes);
        if (maxRewriteTimes > configuredMaxRewriteTimes) {
            metadata.put("evidenceAugmentationOverrideApplied", true);
            metadata.put("evidenceAugmentationOverrideReason",
                "A non-empty MCP result had an actionable evidence gap; one bounded refinement round was preserved.");
        }
        for (int rewriteCount = 1; rewriteCount <= maxRewriteTimes; rewriteCount++) {
            String rewriteSummary = planAttemptRewriteSummary(
                rewriteCount,
                currentPlan,
                currentResult
            );
            observations.add(rewriteSummary);
            InterpretationPlan.Step failedStep = repairRootStep(currentPlan, currentResult);
            String repairReason = evidenceRewriteReason(currentResult, evidenceHistory);
            boolean dagRepairAttempt = !currentResult.success() || failedStep != null;
            if (dagRepairAttempt) {
                recordDagRepairEvent(
                    runtimeAttributes,
                    metadata,
                    "STARTED",
                    rewriteCount,
                    repairReason,
                    failedStep,
                    List.of(),
                    null
                );
            }
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
                repairReason,
                observations,
                tools,
                toolRegistry,
                rewriteRequirements,
                evidenceHistory,
                budgetCaps.configured()
                    ? new InterpretationPlan.ExecutionPolicy(
                        budgetCaps.maxSteps(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        budgetCaps.costBudget(),
                        budgetCaps.latencyBudgetMs(),
                        null
                    )
                    : null
            ));
            InterpretationPlan rewrittenPlan = rewrite.rewrittenPlan();
            InterpretationPlanValidator.ValidationResult rewrittenValidation = rewrite.validation();
            List<String> authoritativeRewritePasses = List.of();
            Object rewriteWorkflowDag = authoritativeWorkflowDagForContinuation(
                authoritativeWorkflowDag,
                rewrittenPlan,
                completedTools
            );
            if (rewrittenPlan != null && hasAuthoritativeWorkflowDag) {
                InterpretationPlanOptimizer.OptimizationResult authoritativeRewrite =
                    new InterpretationPlanOptimizer().optimize(rewrittenPlan, rewriteWorkflowDag);
                rewrittenPlan = authoritativeRewrite.plan() == null ? rewrittenPlan : authoritativeRewrite.plan();
                authoritativeRewritePasses = authoritativeRewrite.appliedPasses();
                rewrittenValidation = validator.validate(
                    rewrittenPlan,
                    toolRegistry,
                    new LinkedHashSet<>(tools == null ? List.of() : tools),
                    rewriteWorkflowDag,
                    authoritativeWorkflowTaskId
                );
            }
            boolean rewrittenValid = rewrittenPlan != null
                && rewrittenValidation != null && rewrittenValidation.valid();
            metadata.put("interpretationPlanRewriteAttempted", true);
            metadata.put("interpretationPlanRewriteCount", rewriteCount);
            metadata.put("interpretationPlanRewriteValid", rewrittenValid);
            metadata.put("interpretationPlanRewriteExecutable",
                rewrittenValidation != null && rewrittenValidation.executable());
            metadata.put("authoritativeWorkflowRewritePasses", authoritativeRewritePasses);
            if (!rewrittenValid && rewrite.errorMessage() != null && !rewrite.errorMessage().isBlank()) {
                metadata.put("interpretationPlanRewriteError", rewrite.errorMessage());
            }
            recordPlanEvolution(
                currentPlan,
                rewrittenPlan,
                rewriteCount + 1,
                rewrittenValid ? "ACCEPTED" : "REJECTED",
                evidenceHistory,
                runtimeAttributes,
                metadata
            );
            List<Map<String, Object>> repairChanges = rewrittenPlan == null
                ? List.of()
                : planChanges(currentPlan, rewrittenPlan);
            if (dagRepairAttempt) {
                recordDagRepairEvent(
                    runtimeAttributes,
                    metadata,
                    rewrittenValid ? "APPLIED" : "REJECTED",
                    rewriteCount,
                    repairReason,
                    failedStep,
                    repairChanges,
                    rewrittenValidation
                );
            }
            runtimeGuard.checkCancelled(cancellationCheck);

            String rewriteStage = rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount;
            recordInterpretationPlanEvaluation(
                rewriteStage,
                rewrittenValidation,
                runtimeAttributes,
                metadata
            );
            if (!rewrittenValid) {
                String evaluationError = firstNonBlank(
                    rewrite.errorMessage(),
                    "rewriter did not return a valid plan"
                );
                observations.add("InterpretationPlan " + rewriteStage
                    + " failed plan evaluation and was not executed: " + evaluationError);
                currentResult = planEvaluationFailure(
                    rewriteStage,
                    rewrittenValidation,
                    evaluationError
                );
                if (rewrittenPlan != null) {
                    currentPlan = rewrittenPlan;
                }
                planAttemptResults.add(currentResult);
                continue;
            }

            if (ToolCallFingerprint.materiallyEquivalent(currentPlan, rewrittenPlan)) {
                duplicateToolPlanSuppressed = true;
                metadata.put("duplicateToolPlanSuppressed", true);
                metadata.put("duplicateToolPlanStage", rewriteStage);
                metadata.put("duplicateToolPlanFingerprints", ToolCallFingerprint.forPlan(rewrittenPlan));
                observations.add("InterpretationPlan " + rewriteStage
                    + " was not executed because its tool calls have no material input change from the previous evidence round.");
                break;
            }

            currentPlan = rewrittenPlan;
            saveInterpretationPlanSnapshot(
                rewriteStage,
                currentPlan,
                tenantId,
                requestId,
                runtimeAttributes,
                metadata
            );
            recordInterpretationPlanExecutionStarted(rewriteStage, currentPlan, runtimeAttributes, metadata);
            Map<String, Object> rewriteExecutionAttributes = workflowAttemptAttributes(
                workflowStateTracker.attributesWithCompletedWorkflowState(
                    runtimeAttributes, completedTools, traces),
                rewriteCount,
                rewriteWorkflowDag
            );
            rewriteExecutionAttributes.put("reusablePlanSteps", List.copyOf(reusablePlanSteps.values()));
            currentResult = runtime.execute(planExecutionRequest(
                currentPlan,
                tenantId,
                requestId,
                conversationId,
                userId,
                tools,
                rewriteExecutionAttributes
            ));
            reusablePlanSteps = reusablePlanSteps(reusablePlanSteps, currentPlan, currentResult);
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
            checkCancelledUnlessBatchEvidence(cancellationCheck, currentResult, metadata);

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
            latestAugmentationDecision = decideEvidenceAugmentation(
                currentEvidence,
                currentResult,
                evidenceExplorationAvailable(
                    currentEvidence,
                    currentResult,
                    tools,
                    rewriteCount < maxRewriteTimes
                ),
                false,
                metadata
            );
            recordEvidenceAugmentationDecision(
                latestAugmentationDecision, rewriteCount + 1, runtimeAttributes, metadata);
            if ("DAG_NO_PROGRESS".equals(currentResult.status())) {
                usablePartialAnalysis = evidenceHistory.stream().anyMatch(this::usableEvidenceAvailable);
                metadata.put("interpretationPlanNoProgressStopped", true);
                metadata.put("interpretationPlanNoProgressStage",
                    rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount);
                observations.add("InterpretationPlan stopped after a rewritten DAG made no execution progress; "
                    + "the persisted evidence chain will be synthesized without another unchanged rewrite.");
                break;
            }
            if (latestAugmentationDecision.decision() == EvidenceAugmentationPolicy.Decision.COMPLETE) {
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
                return finishSynthesizedInterpretationPlanAnswer(
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
            if (latestAugmentationDecision.decision()
                == EvidenceAugmentationPolicy.Decision.ANALYZE_WITH_LIMITATIONS) {
                usablePartialAnalysis = usableEvidenceAvailable(currentEvidence);
                break;
            }
        }

        if (!usablePartialAnalysis && evidenceHistory.stream().anyMatch(this::usableEvidenceAvailable)) {
            usablePartialAnalysis = true;
            latestAugmentationDecision = evidenceAugmentationPolicy.decide(
                new EvidenceAugmentationPolicy.Context(
                    true,
                    false,
                    true,
                    false,
                    false,
                    taskEvidenceRequirement(metadata)
                )
            );
            recordEvidenceAugmentationDecision(
                latestAugmentationDecision, evidenceHistory.size(), runtimeAttributes, metadata);
        }
        metadata.put("interpretationPlanRewriteBudgetExceeded", !usablePartialAnalysis
            && !duplicateToolPlanSuppressed
            && (maxRewriteTimes <= 0
                || firstInteger(metadata.get("interpretationPlanRewriteCount"), 0) >= maxRewriteTimes));
        metadata.put("interpretationPlanFallbackMode", fallbackMode(plan));
        String evidenceCompletionReason = usablePartialAnalysis
            ? "evidence_partial_analysis"
            : duplicateToolPlanSuppressed
                ? "duplicate_tool_plan_suppressed"
                : "evidence_iteration_limit";
        metadata.put("stopReason", evidenceCompletionReason);
        metadata.put("interpretationPlanEvidenceIterationCount", evidenceHistory.size());
        if (!evidenceHistory.isEmpty()) {
            recordEvidenceStopState(
                metadata,
                evidenceHistory.get(evidenceHistory.size() - 1),
                evidenceCompletionReason,
                evidenceHistory.size()
            );
        }
        observations.add(usablePartialAnalysis
            ? "InterpretationPlan has usable evidence and will produce a stage analysis with explicit limitations."
            : duplicateToolPlanSuppressed
            ? "InterpretationPlan stopped before a duplicate tool call; final answer will use the persisted evidence chain."
            : "InterpretationPlan completed its evidence revision budget; final answer will reconcile all persisted evidence and unresolved gaps.");
        runMissingMandatoryWorkflowTools(
            activeChatModel,
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
        AgentExecutionResult planWorkflowBlockedResult = finishInterpretationPlanWorkflowBlockedIfPending(
            traces,
            metadata,
            observations,
            "interpretation_plan_workflow_incomplete",
            "InterpretationPlan workflow guard blocked final_answer before all required DAG steps completed."
        );
        if (planWorkflowBlockedResult != null) {
            return planWorkflowBlockedResult;
        }
        if (!planAttemptResults.isEmpty()) {
            String synthesisStage = Boolean.TRUE.equals(metadata.get("mandatoryWorkflowRecoveredAfterPlan"))
                ? "mandatory_workflow_completed"
                : "attempts_exhausted";
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
                synthesisStage
            );
            return finishSynthesizedInterpretationPlanAnswer(
                activeChatModel,
                query,
                systemPrompt,
                traces,
                metadata,
                observations,
                synthesizedAnswer,
                cancellationCheck,
                evidenceCompletionReason
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

    private AgentExecutionResult finishInterpretationPlanWorkflowBlockedIfPending(
        List<InteractionToolTrace> traces,
        Map<String, Object> metadata,
        List<String> observations,
        String stopReason,
        String reason
    ) {
        if (metadata == null || !Boolean.TRUE.equals(metadata.get("interpretationPlanWorkflowBlocked"))) {
            return null;
        }
        List<String> missingTools = metadataStringList(metadata, "interpretationPlanWorkflowMissingTools");
        List<String> missingStepIds = metadataStringList(metadata, "interpretationPlanWorkflowMissingPlanStepIds");
        if (!missingTools.isEmpty()) {
            Set<String> successfullyCompletedTools = traces == null
                ? Set.of()
                : traces.stream()
                    .filter(Objects::nonNull)
                    .filter(InteractionToolTrace::isSuccess)
                    .map(InteractionToolTrace::getToolName)
                    .filter(tool -> tool != null && !tool.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<String> unresolvedTools = workflowTools.missingMandatoryTools(
                missingTools,
                successfullyCompletedTools
            );
            if (unresolvedTools.size() != missingTools.size()) {
                metadata.put("interpretationPlanWorkflowMissingTools", unresolvedTools);
                metadata.put("interpretationPlanWorkflowReconciledFromTerminalEvents", true);
                observations.add("InterpretationPlan workflow guard reconciled later successful tool observations. Remaining tools: "
                    + unresolvedTools + ".");
                missingTools = unresolvedTools;
            }
        }
        if (missingTools.isEmpty() && missingStepIds.isEmpty()) {
            metadata.put("interpretationPlanWorkflowBlocked", false);
            metadata.put("interpretationPlanWorkflowResolvedAfterFallback", true);
            return null;
        }
        metadata.put("stopReason", stopReason);
        metadata.put("fatalExecutionBlocked", true);
        metadata.put("mandatoryWorkflowBlocked", true);
        metadata.put("mandatoryWorkflowCompleted", false);
        metadata.put("mandatoryWorkflowPending", true);
        metadata.put("errorCode", "PLAN_INVALID_REQUIRED_STEP_NOT_EXECUTED");
        metadata.put("errorMessage", "PLAN_INVALID_REQUIRED_STEP_NOT_EXECUTED: " + reason
            + " Missing tools: " + missingTools
            + "; missing plan steps: " + missingStepIds);
        observations.add(reason + " Missing tools: " + missingTools
            + "; missing plan steps: " + missingStepIds);
        String deterministicFailure = "InterpretationPlan workflow is incomplete. final_answer was blocked before all required DAG steps completed."
            + " Missing tools: " + missingTools
            + "; missing plan steps: " + missingStepIds
            + ". Completed tool evidence has been preserved, but this run will not synthesize a final answer from an incomplete workflow.";
        return answerFinalizer.finishExecution(deterministicFailure, traces, metadata, observations);
    }

    private AgentExecutionResult finishSynthesizedInterpretationPlanAnswer(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        List<InteractionToolTrace> traces,
        Map<String, Object> metadata,
        List<String> observations,
        String synthesizedAnswer,
        BooleanSupplier cancellationCheck,
        String stopReason
    ) {
        if (hasBatchExecutionTrace(traces)
            || Boolean.TRUE.equals(metadata.get("cumulativeBatchEvidencePresent"))) {
            metadata.put("stopReason", stopReason);
            metadata.put("reservedFinalizationCalls", 1);
            metadata.put("batchFinalizationModelCalls", 1);
            metadata.put("answerReviewSkipped", true);
            metadata.put("answerReviewSkipReason",
                "batch diagnostics reserve the single post-execution model call for final synthesis");
            return answerFinalizer.finishExecution(synthesizedAnswer, traces, metadata, observations);
        }
        return answerFinalizer.finishReviewedAnswer(
            activeChatModel,
            query,
            systemPrompt,
            traces,
            metadata,
            observations,
            synthesizedAnswer,
            cancellationCheck,
            stopReason
        );
    }

    /**
     * Prevents a final synthesis model from copying an external evidence handle out of chat
     * history or a behavioral system prompt. External document handles are opaque capabilities:
     * only handles present in this execution's persisted tool evidence may be shown as current
     * results. The guard is deliberately protocol-based and contains no domain vocabulary.
     */
    String removeUnsupportedCurrentTurnDocumentReferences(
        String answer,
        InterpretationPlanRuntime.ExecutionResult currentEvidence,
        Map<String, Object> metadata
    ) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        Set<String> supported = toolOutputDocumentIds(stringify(currentEvidence));
        Matcher answerIds = TOOL_OUTPUT_DOCUMENT_ID.matcher(answer);
        if (!answerIds.find()) {
            return answer;
        }
        List<String> keptLines = new ArrayList<>();
        int removed = 0;
        for (String line : answer.split("\\R", -1)) {
            Set<String> referenced = toolOutputDocumentIds(line);
            if (!referenced.isEmpty() && !supported.containsAll(referenced)) {
                removed++;
                continue;
            }
            keptLines.add(line);
        }
        if (removed == 0) {
            return answer;
        }
        String guarded = String.join("\n", keptLines).replaceAll("\n{3,}", "\n\n").trim();
        if (metadata != null) {
            metadata.put("unsupportedCurrentTurnDocumentReferencesRemoved", removed);
            metadata.put("currentTurnDocumentReferenceGuardApplied", true);
        }
        log.warn("Removed unsupported external evidence references from final synthesis: count={}", removed);
        return guarded;
    }

    private Set<String> toolOutputDocumentIds(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = TOOL_OUTPUT_DOCUMENT_ID.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return Set.copyOf(ids);
    }

    private boolean hasBatchExecutionTrace(List<InteractionToolTrace> traces) {
        return traces != null && traces.stream().anyMatch(trace ->
            trace != null
                && trace.getRuntimeMetadata() != null
                && Boolean.TRUE.equals(trace.getRuntimeMetadata().get("batchExecution")));
    }

    private void checkCancelledUnlessBatchEvidence(
        BooleanSupplier cancellationCheck,
        InterpretationPlanRuntime.ExecutionResult result,
        Map<String, Object> metadata
    ) {
        try {
            runtimeGuard.checkCancelled(cancellationCheck);
        } catch (CancellationException ex) {
            String message = firstNonBlank(ex.getMessage(), "");
            if (!hasBatchExecutionResult(result)
                || !message.toLowerCase(Locale.ROOT).contains("timed out")) {
                throw ex;
            }
            metadata.put("stopReason", "time_budget_exhausted");
            metadata.put("executionStatus",
                DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue());
            metadata.put("completedEvidencePreservedAfterTimeout", true);
        }
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
        metadata.put("deterministicMandatoryWorkflowFailure", true);
        String deterministicFailure = "必需工具 "
            + String.join(", ", missingMandatoryTools)
            + " 未执行到终态，本次执行被工作流依赖校验阻断。"
            + " 已完成的工具证据和失败原因均已保留，可在补齐依赖后继续诊断。";
        return answerFinalizer.finishExecution(deterministicFailure, traces, metadata, observations);
    }

    private ModelAssistedRetrievalBridge.RetrievalEvidenceContext templateRetrievalEvidenceContext(
        String userQuery,
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed
    ) {
        Map<Integer, Object> outputs = new LinkedHashMap<>();
        if (completed != null) {
            completed.forEach((stepId, execution) -> {
                if (stepId != null && execution != null && execution.success() && execution.output() != null) {
                    outputs.put(stepId, execution.output());
                }
            });
        }
        return new ModelAssistedRetrievalBridge.RetrievalEvidenceContext(userQuery, outputs);
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

    private Map<String, Object> workflowAttemptAttributes(Map<String, Object> runtimeAttributes,
                                                          int attempt,
                                                          Object authoritativeWorkflowDag) {
        Map<String, Object> attributes = workflowAttemptAttributes(runtimeAttributes, attempt);
        if (authoritativeWorkflowDag != null) {
            attributes.put("authoritativeWorkflowDag", authoritativeWorkflowDag);
        }
        return attributes;
    }

    Map<String, Object> interpretationPlanInitialAttributes(Map<String, Object> runtimeAttributes,
                                                             List<InteractionToolTrace> traces) {
        Set<String> completedTools = completedWorkflowToolsFromEvents(
            runtimeAttributes,
            workflowStateTracker.completedToolsFromTraces(traces)
        );
        Map<String, Object> attributes = new LinkedHashMap<>(
            workflowStateTracker.attributesWithCompletedWorkflowState(runtimeAttributes, completedTools, traces)
        );
        pinDagGovernanceContract(attributes);
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
        String raw;
        try {
            raw = activeChatModel.chat(prompt);
        } catch (RuntimeException ex) {
            log.warn("agentModelFailed phase=interpretation_plan_dag_decision decisionCount={} promptChars={} errorType={} error={}",
                request.decisionCount(),
                prompt.length(),
                ex.getClass().getSimpleName(),
                firstNonBlank(ex.getMessage(), "(no message)"));
            return dagDecisionFailureFallback(request, ex);
        }
        log.info("agentModelResponse phase=interpretation_plan_dag_decision decisionCount={} durationMs={} responseChars={}",
            request.decisionCount(),
            System.currentTimeMillis() - startedAt,
            raw == null ? 0 : raw.length());
        log.debug("agentModelRawOutput phase=interpretation_plan_dag_decision decisionCount={} raw=\n{}",
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
        String modelReason = firstNonBlank(
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
        metadata.put("modelReason", modelReason);
        metadata.put("runtimeStatusGrounded", true);
        if (reviewAnswer != null && !reviewAnswer.isBlank()) {
            metadata.put("modelReviewAnswer", reviewAnswer);
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
        String runtimeStatus = runtimeDagExecutionStatus(request);
        metadata.put("reviewAnswer", runtimeStatus);
        metadata.put("runtimeExecutionStatus", runtimeStatus);
        String groundedReason = "Runtime accepted DAG action=" + action
            + " stepIds=" + stepIds + ". " + runtimeStatus;
        log.info("agentDagDecisionGrounded decisionCount={} action={} stepIds={} status={}",
            request.decisionCount(), action, stepIds, runtimeStatus);
        return new InterpretationPlanRuntime.DagDecision(
            protocolVersion, action, stepIds, groundedReason, null, metadata);
    }

    String buildInterpretationPlanDagDecisionPrompt(String query,
                                                    String systemPrompt,
                                                    InterpretationPlanRuntime.DagDecisionRequest request) {
        ContextTokenEstimator.Size evidenceSize = estimateDagDecisionEvidenceSize(request);
        int dagEvidenceTokenBudget = dagDecisionEvidenceTokenBudget();
        boolean compressionEnabled = evidenceSize.tokens() > dagEvidenceTokenBudget;
        String prompt = renderInterpretationPlanDagDecisionPrompt(
            query,
            systemPrompt,
            request,
            compressionEnabled,
            evidenceSize
        );
        if (compressionEnabled) {
            ContextTokenEstimator.Size compressedSize = contextTokenEstimator.estimate(prompt);
            log.warn("agentContextCompression phase=interpretation_plan_dag_decision enabled=true evidenceBudgetTokens={} originalEvidenceTokens={} originalEvidenceChars={} compressedContextTokens={} compressedContextChars={} executionCount={}",
                dagEvidenceTokenBudget,
                evidenceSize.tokens(),
                evidenceSize.chars(),
                compressedSize.tokens(),
                prompt.length(),
                request == null || request.executions() == null ? 0 : request.executions().size());
        }
        return prompt;
    }

    private String renderInterpretationPlanDagDecisionPrompt(
        String query,
        String systemPrompt,
        InterpretationPlanRuntime.DagDecisionRequest request,
        boolean compressionEnabled,
        ContextTokenEstimator.Size evidenceSize
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("System policy inheritance: the validated plan already carries the user intent, scope, "
            + "constraints, and approved tools. This controller may narrow execution but must not expand that scope.\n\n");
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
        prompt.append("- Keep decisions within the current user query. Capability exclusions, notAssessedClaims, unsupportedClaims, and other tool contract boundaries are not pending work unless they block an explicitly requested deliverable.\n");
        prompt.append("- completed_step_ids and executionLock are authoritative runtime state. Never re-run or override a completed/locked step, even if you think the state is contradictory.\n");
        prompt.append("- A completed step record with success=true means the tool execution succeeded. This status is authoritative and may not be rewritten as failed by reason or review_answer.\n");
        prompt.append("- Keep execution status, result completeness, and evidence sufficiency separate. outputTruncated=true, partialEvidence=true, or evidenceSufficiency=INSUFFICIENT describes incomplete evidence; none of these means the tool call failed.\n");
        prompt.append("- A successful empty collection does not satisfy a required discovery goal. When a materially revised retrieval path remains within the plan rewrite and tool budgets, request rewrite_plan; otherwise select final_answer with a bounded partial-evidence instruction. Never require an indexed child such as items[0] from an empty collection.\n");
        prompt.append("- Describe a step as failed only when its executed record has success=false or a non-empty execution error. Never call a successful truncated result a failed step.\n");
        prompt.append("- Do not select a step until all of its depends_on steps are in completed_step_ids.\n");
        prompt.append("- Use execute_parallel_steps only when execution_policy.allow_parallel is true and every selected step is independently ready.\n");
        prompt.append("- Select the final_answer step only when it is the last remaining executable step and evidence is sufficient.\n");
        prompt.append("- Do not emit final_answer in your JSON. If you need to leave a diagnostic, write review_answer; Java will produce final_answer only from the final plan step.\n");
        prompt.append("- If a required dependency failed, request rewrite_plan or abort instead of forcing a dependent step.\n");
        prompt.append("- After template discovery, inspect the selected template's parameterSchema/requiredParameters before selecting its execution step.\n");
        prompt.append("- When the selected template declares parameters, analyze and organize an evidence-based parameter profile in parameter_protocols using ")
            .append(InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION)
            .append(". Use the exact declared parameter names and exact discovered template_id.\n");
        prompt.append("- Each argument must be evidence-bearing: use {value, source:user_query, evidence:{quote}} for an exact User-query fact, or {value, source:tool_result, evidence:{step_id,output_path}} for an exact value in a successful completed step. Runtime re-reads and compares every cited source before execution.\n");
        prompt.append("- Add analysis_summary to explain the parameter profile. Never use model inference as a source, and never copy parameterSchema, defaults, routing fields, credentials, or an entire template object into arguments. Runtime owns defaults, type compilation, routing and execution.\n");
        prompt.append("- Put parameters that cannot be proven by the User query or a completed tool result in unresolved_parameters. When a required parameter is unresolved, request rewrite_plan instead of executing with an invented or empty value.\n");
        prompt.append("- Do not put a parameter in unresolved_parameters merely because the user omitted it when the selected template declares a non-empty default for that field. Template defaults are authoritative contract evidence; emit only evidence-backed overrides and let Runtime apply the remaining defaults.\n");
        if (compressionEnabled) {
            prompt.append("- Context compression is active because the complete DAG evidence exceeded its token budget.\n");
            prompt.append("- Compressed tool outputs below are semantic scheduling projections. Full results remain authoritative in Runtime step records and tool traces.\n");
            prompt.append("- Use outputFacts, review metadata, counts, status, and completeness markers to schedule the next node. Never infer that omitted compressed details were absent from the tool result.\n");
        } else {
            prompt.append("- Context compression is inactive. Executed tool outputs below are complete Runtime inputs.\n");
        }
        prompt.append("- Do not call tools directly; Java will only execute the step ids you choose after safety validation.\n\n");
        prompt.append("User query:\n").append(query == null ? "" : query).append("\n\n");
        prompt.append("decision_count: ").append(request.decisionCount()).append("\n");
        prompt.append("remaining_step_ids: ").append(request.remainingStepIds() == null ? List.of() : request.remainingStepIds()).append("\n");
        prompt.append("completed_step_ids: ").append(request.completedStepIds() == null ? List.of() : request.completedStepIds()).append("\n");
        prompt.append("context_compression: ")
            .append(Map.of(
                "enabled", compressionEnabled,
                "maxTokens", contextBudget.maxTokens(),
                "reservedSystemTokens", contextBudget.reservedSystemTokens(),
                "reservedHistoryTokens", contextBudget.reservedHistoryTokens(),
                "reservedOutputTokens", contextBudget.reservedOutputTokens(),
                "availableEvidenceTokens", dagDecisionEvidenceTokenBudget(),
                "evidenceTokens", evidenceSize.tokens(),
                "evidenceChars", evidenceSize.chars()
            ))
            .append("\n");
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
                    .append(stringify(compressionEnabled
                        ? dagDecisionModelOutputSnapshot(
                            execution,
                            Math.max(1, request.executions().size()),
                            dagDecisionEvidenceTokenBudget()
                        )
                        : execution.output()))
                    .append("\n");
                if (!compressionEnabled
                    && execution.metadata() != null
                    && !execution.metadata().isEmpty()) {
                    prompt.append("  metadata: ")
                        .append(stringify(ToolLogSummarizer.summarize(
                            execution.metadata(), DAG_DECISION_OUTPUT_SUMMARY_CHARS)))
                        .append("\n");
                }
            }
        }
        prompt.append("\nReturn only the decision JSON.");
        return prompt.toString();
    }

    String runtimeDagExecutionStatus(InterpretationPlanRuntime.DagDecisionRequest request) {
        List<Integer> succeeded = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        List<Integer> partialEvidence = new ArrayList<>();
        if (request != null && request.executions() != null) {
            for (InterpretationPlanRuntime.StepExecution execution : request.executions()) {
                if (execution == null || execution.stepId() == null) {
                    continue;
                }
                if (execution.success()) {
                    succeeded.add(execution.stepId());
                    Map<String, Object> stepMetadata = execution.metadata() == null
                        ? Map.of() : execution.metadata();
                    if (Boolean.TRUE.equals(stepMetadata.get("partialEvidence"))
                        || "INSUFFICIENT".equals(String.valueOf(stepMetadata.get("evidenceSufficiency")))
                        || outputIsTruncated(execution.output())) {
                        partialEvidence.add(execution.stepId());
                    }
                } else {
                    failed.add(execution.stepId());
                }
            }
        }
        return "Authoritative Runtime state: succeededStepIds=" + succeeded
            + ", failedStepIds=" + failed
            + ", partialEvidenceStepIds=" + partialEvidence + ".";
    }

    private boolean outputIsTruncated(Object output) {
        Map<String, Object> root = objectMap(output);
        return Boolean.TRUE.equals(booleanValue(firstObject(root,
            "outputTruncated", "truncated", "isTruncated")));
    }

    Object dagDecisionPromptOutputSnapshot(String toolName, Object output) {
        return ToolLogSummarizer.summarizeResult(toolName, output);
    }

    InterpretationPlanRuntime.DagDecision dagDecisionFailureFallback(
        InterpretationPlanRuntime.DagDecisionRequest request,
        RuntimeException failure
    ) {
        if (request != null && request.remainingStepIds() != null
            && request.remainingStepIds().size() == 1 && request.plan() != null
            && request.plan().plan() != null && request.plan().plan().steps() != null) {
            Integer remainingStepId = request.remainingStepIds().iterator().next();
            InterpretationPlan.Step remainingStep = request.plan().plan().steps().stream()
                .filter(step -> step != null && Objects.equals(step.id(), remainingStepId))
                .findFirst()
                .orElse(null);
            boolean dependenciesCompleted = remainingStep != null
                && (remainingStep.dependsOn() == null
                    || (request.completedStepIds() != null
                        && request.completedStepIds().containsAll(remainingStep.dependsOn())));
            if (dependenciesCompleted && "final_answer".equalsIgnoreCase(remainingStep.actionType())) {
                return InterpretationPlanRuntime.DagDecision.executeStep(
                    remainingStepId,
                    "DAG controller model failed after all dependencies completed; Runtime selected the sole remaining final_answer step."
                );
            }
        }
        return InterpretationPlanRuntime.DagDecision.abort(
            "DAG controller model failed: "
                + firstNonBlank(failure == null ? null : failure.getMessage(),
                    failure == null ? "unknown error" : failure.getClass().getSimpleName())
        );
    }

    Map<String, Object> dagDecisionOutputSnapshot(Object output) {
        return objectMap(output);
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return asStringObjectMap(map);
        }
        if (value instanceof String text) {
            return asMap(text);
        }
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(value, Map.class);
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> objectMapList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : values) {
            Map<String, Object> record = objectMap(item);
            if (!record.isEmpty()) {
                records.add(record);
            }
        }
        return List.copyOf(records);
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
        InterpretationPlanRuntime.ExecutionResult cumulativeEvidenceResult =
            cumulativeEvidenceResult(result, attemptResults);
        if (metadata != null && hasBatchExecutionResult(cumulativeEvidenceResult)) {
            metadata.put("cumulativeBatchEvidencePresent", true);
        }
        try {
            runtimeGuard.checkCancelled(cancellationCheck);
        } catch (CancellationException ex) {
            if (!hasBatchExecutionResult(cumulativeEvidenceResult)) {
                throw ex;
            }
            metadata.put("stopReason", "time_budget_exhausted");
            metadata.put("executionStatus",
                DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue());
            metadata.put("interpretationPlanSummaryGenerated", false);
            metadata.put("interpretationPlanSummaryFailure", firstNonBlank(ex.getMessage(), "Agent run timed out"));
            return "";
        }
        if (activeChatModel == null) {
            return result == null ? "" : firstNonBlank(result.finalAnswer(), "");
        }
        List<AgentObservation> storedObservations = storedInterpretationPlanObservations(runtimeAttributes);
        RecordCoverageBundle recordCoverage = buildRecordCoverageBundle(
            activeChatModel, query, cumulativeEvidenceResult, runtimeAttributes, metadata, cancellationCheck);
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
        String prompt;
        try {
            prompt = buildInterpretationPlanSummaryPrompt(
                query,
                systemPrompt,
                result,
                attemptResults,
                observations,
                storedObservations
            );
        } catch (RuntimeException ex) {
            if (!summarizeAvailableResults(runtimeAttributes)) {
                throw ex;
            }
            log.error("Final synthesis evidence projection failed; using structural fallback. "
                    + "runId={} stage={} errorType={} error={}",
                firstNonBlank(stringValue(runtimeAttributes == null
                    ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)), ""),
                stage, ex.getClass().getName(), ex.getMessage(), ex);
            metadata.put("interpretationPlanEvidenceProjectionFallback", true);
            metadata.put("interpretationPlanEvidenceProjectionFailureType", ex.getClass().getName());
            metadata.put("interpretationPlanEvidenceProjectionFailure",
                firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName()));
            prompt = buildStructuralFallbackSummaryPrompt(query, systemPrompt, result, attemptResults);
        }
        if (!recordCoverage.promptEvidence().isBlank()) {
            prompt += "\n\n" + recordCoverage.promptEvidence();
        }
        String reviewEvidenceContext = interpretationPlanReviewEvidenceContext(prompt);
        if (metadata != null && !reviewEvidenceContext.isBlank()) {
            metadata.put("modelAnalysisReviewContext", reviewEvidenceContext);
            metadata.put("modelEvidenceReviewRewriteAllowed", true);
            metadata.put("modelAnalysisReviewContractVersion", "model_analysis_repair_v1");
        }
        String runId = stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE));
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=interpretation_plan_summary runId={} stage={} modelClass={} promptChars={} stepCount={} storedObservationCount={}",
            firstNonBlank(runId, ""),
            stage,
            activeChatModel.getClass().getName(),
            prompt.length(),
            result == null || result.steps() == null ? 0 : result.steps().size(),
            storedObservations.size());
        String answer;
        try {
            answer = activeChatModel.chat(prompt);
        } catch (RuntimeException ex) {
            metadata.put("interpretationPlanSummaryGenerated", false);
            metadata.put("interpretationPlanSummaryFailure",
                firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName()));
            if (summarizeAvailableResults(runtimeAttributes)) {
                String fallbackAnswer = buildDeterministicAvailableResultAnswer(cumulativeEvidenceResult);
                fallbackAnswer = ensureCompleteRecordCoveragePresented(
                    fallbackAnswer, recordCoverage, metadata);
                metadata.put("interpretationPlanDeterministicSummaryFallback", true);
                metadata.putIfAbsent("executionStatus", "PARTIAL_RESULT_PRESENTED");
                AnalysisSummaryResult fallbackResult = governedFinalSummaryResult(
                    stage, fallbackAnswer, "DETERMINISTIC_FINAL_FALLBACK", recordCoverage,
                    runtimeAttributes, metadata);
                return fallbackResult.content();
            }
            metadata.putIfAbsent("executionStatus", "NO_PRESENTABLE_RESULT");
            return "";
        }
        answer = ensureConcreteBatchEvidencePresented(
            answer, query, cumulativeEvidenceResult, metadata);
        answer = ensureCompleteRecordCoveragePresented(answer, recordCoverage, metadata);
        answer = removeUnsupportedCurrentTurnDocumentReferences(
            answer, cumulativeEvidenceResult, metadata);
        String governedContent = answer == null || answer.isBlank()
            ? (result == null ? "" : firstNonBlank(result.finalAnswer(), ""))
            : answer;
        AnalysisSummaryResult finalSummaryResult = governedFinalSummaryResult(
            stage, governedContent,
            answer == null || answer.isBlank() ? "MODEL_EMPTY_RUNTIME_FINAL_FALLBACK" : "MODEL_FINAL_SUMMARY",
            recordCoverage, runtimeAttributes, metadata);
        answer = finalSummaryResult.content();
        log.info("agentModelResponse phase=interpretation_plan_summary runId={} stage={} durationMs={} responseChars={}",
            firstNonBlank(runId, ""),
            stage,
            System.currentTimeMillis() - startedAt,
            answer == null ? 0 : answer.length());
        log.info("agentModelOutput phase=interpretation_plan_summary runId={} stage={} answer=\n{}",
            firstNonBlank(runId, ""),
            stage,
            ModelProtocolJson.prettyJsonForLog(answer));
        answerCandidateCollector.register(
            metadata,
            AnswerCandidateCollector.FINAL_SYNTHESIS,
            answer
        );
        if (metadata != null) {
            metadata.put("interpretationPlanSummaryGenerated", true);
            metadata.put("interpretationPlanSummaryStage", stage);
            metadata.put("interpretationPlanAttemptCount", attemptResults == null ? 0 : attemptResults.size());
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
                "answerPreview", preview(answer),
                "analysisSummaryResult", finalSummaryResult.toMap(),
                "tenantId", finalSummaryResult.isolationScope().tenantId(),
                "runId", finalSummaryResult.isolationScope().runId()
            )
        );
        return answer;
    }

    private AnalysisSummaryResult governedFinalSummaryResult(String stage,
                                                              String content,
                                                              String outcome,
                                                              RecordCoverageBundle coverage,
                                                              Map<String, Object> runtimeAttributes,
                                                              Map<String, Object> metadata) {
        Map<String, Object> coverageMap = Map.of(
            "returnedRecordCount", coverage.returnedRecordCount(),
            "processedRecordCount", coverage.processedRecordCount(),
            "coverageComplete", coverage.coverageComplete(),
            "sourceContentComplete", coverage.sourceContentComplete(),
            "iterationCount", coverage.iterations(),
            "summaryResultCount", coverage.summaryResults().size()
        );
        AnalysisSummaryResult summaryResult = analysisSummaryGovernanceBridge.finalResult(
            summaryIsolationScope(coverage, metadata),
            stage, content, outcome, coverageMap, coverage.summaryResults());
        if (metadata != null) {
            metadata.put("analysisSummaryResult", summaryResult.toMap());
            metadata.put("analysisSummaryResultSchemaVersion", AnalysisSummaryResult.SCHEMA_VERSION);
        }
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            "Governed final analysis summary recorded for " + summaryResult.resultId() + ".",
            "analysis_summary_governance",
            metadataOf(
                "type", "analysis_summary_result",
                "analysisSummaryResult", summaryResult.toMap(),
                "tenantId", summaryResult.isolationScope().tenantId(),
                "runId", summaryResult.isolationScope().runId()
            )
        );
        return summaryResult;
    }

    private GovernanceIsolationScope summaryIsolationScope(RecordCoverageBundle coverage,
                                                            Map<String, Object> metadata) {
        if (coverage != null && coverage.summaryResults() != null && !coverage.summaryResults().isEmpty()) {
            return coverage.summaryResults().get(0).isolationScope();
        }
        return governanceIsolationScope(metadata, Map.of());
    }

    private GovernanceIsolationScope governanceIsolationScope(Map<String, Object> metadata,
                                                               Map<String, Object> runtimeAttributes) {
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        Map<String, Object> safeAttributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
        return GovernanceIsolationScope.runtime(
            stringValue(safeMetadata.get("tenantId")),
            stringValue(safeMetadata.get("userId")),
            firstNonBlank(stringValue(safeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)),
                stringValue(safeMetadata.get("agentRunId"))),
            stringValue(safeMetadata.get("requestId")),
            stringValue(safeMetadata.get("conversationId"))
        );
    }

    /**
     * Builds the evidence view used by deterministic final-answer guards. Incremental DAG repair
     * may move an executed batch into an earlier attempt while the latest attempt only contains
     * repaired downstream steps. Prompt synthesis already sees every attempt; the immutable
     * record-coverage and concrete-value guards must see the same cumulative evidence chain.
     */
    InterpretationPlanRuntime.ExecutionResult cumulativeEvidenceResult(
        InterpretationPlanRuntime.ExecutionResult latest,
        List<InterpretationPlanRuntime.ExecutionResult> attempts
    ) {
        List<InterpretationPlanRuntime.ExecutionResult> sources = attempts == null || attempts.isEmpty()
            ? (latest == null ? List.of() : List.of(latest))
            : attempts;
        List<InterpretationPlanRuntime.StepExecution> steps = new ArrayList<>();
        List<ToolCallResult> batchChildren = new ArrayList<>();
        Set<String> seenBatchChildren = new LinkedHashSet<>();
        Set<String> seenSteps = new LinkedHashSet<>();
        for (InterpretationPlanRuntime.ExecutionResult attempt : sources) {
            if (attempt == null || attempt.steps() == null) {
                continue;
            }
            for (InterpretationPlanRuntime.StepExecution step : attempt.steps()) {
                if (step == null || step.output() == null) {
                    continue;
                }
                if (step.output() instanceof ToolCallBatchResult batch) {
                    for (ToolCallResult child : batch.results()) {
                        String evidenceIdentity = firstNonBlank(child.evidenceId(), "");
                        String identity = firstNonBlank(child.templateId(),
                            firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "batch-child")))
                            + "|" + (evidenceIdentity.isBlank()
                                ? stringify(child.output()) : evidenceIdentity);
                        if (seenBatchChildren.add(identity)) {
                            batchChildren.add(child);
                        }
                    }
                    continue;
                }
                String identity = firstNonBlank(step.toolName(), step.actionType())
                    + "|" + stringify(step.output());
                if (seenSteps.add(identity)) {
                    steps.add(step);
                }
            }
        }
        if (!batchChildren.isEmpty()) {
            int succeeded = (int) batchChildren.stream()
                .filter(child -> "SUCCESS".equalsIgnoreCase(child.status()))
                .count();
            int failed = batchChildren.size() - succeeded;
            ToolCallBatchResult cumulativeBatch = new ToolCallBatchResult(
                "cumulative-interpretation-plan-evidence",
                "CUMULATIVE",
                "",
                "",
                failed == 0 ? "SUCCESS" : (succeeded > 0 ? "PARTIAL_SUCCESS" : "FAILED"),
                new ToolCallBatchResult.Summary(
                    batchChildren.size(), succeeded, failed, 0, 0, batchChildren.size()),
                batchChildren
            );
            steps.add(new InterpretationPlanRuntime.StepExecution(
                -1,
                "mcp_tool",
                "cumulative_batch_execution",
                succeeded > 0,
                cumulativeBatch,
                null,
                null,
                null,
                0L,
                Map.of("batchExecution", true, "cumulativeEvidence", true)
            ));
        }
        if (steps.isEmpty() && latest != null) {
            return latest;
        }
        return new InterpretationPlanRuntime.ExecutionResult(
            latest == null ? "cumulative_evidence" : latest.status(),
            latest == null || latest.success(),
            latest != null && latest.approvalRequired(),
            latest == null ? null : latest.errorMessage(),
            latest == null ? null : latest.finalAnswer(),
            List.copyOf(steps),
            latest == null || latest.metadata() == null ? Map.of() : latest.metadata(),
            latest == null ? 0L : latest.durationMs()
        );
    }

    private String interpretationPlanReviewEvidenceContext(String synthesisPrompt) {
        if (synthesisPrompt == null || synthesisPrompt.isBlank()) {
            return "";
        }
        int start = synthesisPrompt.indexOf("Executed plan attempts (");
        if (start < 0) {
            return "";
        }
        int end = synthesisPrompt.lastIndexOf("\nReturn only the final user-facing Markdown answer");
        if (end <= start) {
            end = synthesisPrompt.length();
        }
        return synthesisPrompt.substring(start, end);
    }

    private boolean hasBatchExecutionResult(InterpretationPlanRuntime.ExecutionResult result) {
        return result != null
            && result.steps() != null
            && result.steps().stream().anyMatch(step ->
                step != null
                    && step.toolExecution() != null
                    && step.toolExecution().audit() != null
                    && Boolean.TRUE.equals(step.toolExecution().audit().get("batchExecution")));
    }

    String buildInterpretationPlanSummaryPrompt(String query,
                                                String systemPrompt,
                                                InterpretationPlanRuntime.ExecutionResult result,
                                                List<String> observations,
                                                List<AgentObservation> storedObservations) {
        return buildInterpretationPlanSummaryPrompt(
            query,
            systemPrompt,
            result,
            result == null ? List.of() : List.of(result),
            observations,
            storedObservations
        );
    }

    String buildInterpretationPlanSummaryPrompt(String query,
                                                String systemPrompt,
                                                InterpretationPlanRuntime.ExecutionResult result,
                                                List<InterpretationPlanRuntime.ExecutionResult> attemptResults,
                                                List<String> observations,
                                                List<AgentObservation> storedObservations) {
        List<InterpretationPlanRuntime.ExecutionResult> results = attemptResults == null || attemptResults.isEmpty()
            ? (result == null ? List.of() : List.of(result))
            : attemptResults;
        ContextTokenEstimator.Size evidenceSize = estimateSummaryEvidenceSize(
            results, observations, storedObservations);
        int summaryEvidenceBudget = Math.min(
            contextBudget.availableEvidenceTokens(), SUMMARY_EVIDENCE_TOKEN_BUDGET);
        boolean compressionEnabled = evidenceSize.tokens() > summaryEvidenceBudget;
        int executionCount = Math.max(1, results.stream()
            .filter(Objects::nonNull)
            .map(InterpretationPlanRuntime.ExecutionResult::steps)
            .filter(Objects::nonNull)
            .mapToInt(List::size)
            .sum());
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are the final step-by-step answer synthesizer for MCP InterpretationPlan attempts.\n");
        prompt.append("Answer the user in Chinese using only the executed attempt records, model review decisions, and stored observations.\n");
        prompt.append("Return a polished Markdown document, not a single plain paragraph. Use concise headings and lists when they improve readability.\n");
        prompt.append("Do not wrap the Markdown in code fences and do not output JSON.\n");
        prompt.append("Primary answer contract (outcome-first):\n");
        prompt.append("- Start by directly answering the user's requested deliverable. For a summary request, synthesize the document's main content and value points first.\n");
        prompt.append("- Use this answer order unless the user requests another format: direct conclusion; evidence-backed findings grouped by the user's requested dimensions; coverage and material limitations; smallest useful next action only when evidence is incomplete.\n");
        prompt.append("- Do not begin with asset discovery, template counts, tool calls, plan attempts, or execution chronology. Include execution details only when they explain evidence coverage, failure, or provenance requested by the user.\n");
        prompt.append("- Do not make the tool evidence list, document heading path, execution trace, or JSON field names the body of the answer.\n");
        prompt.append("- Use source/document references only as support after the synthesized conclusion. Avoid copying retrieved heading paths or raw chunk structure unless the user explicitly asks for provenance.\n");
        prompt.append("- If retrieved text is noisy, deduplicate repeated headings, repair line-break artifacts, and summarize the underlying meaning instead of echoing the retrieval format.\n");
        prompt.append("- If a required metadata search was blocked by workflow dependency validation, report it as a runtime workflow blockage. Do not claim that enterprise standards, terms, dictionaries, or other governed metadata do not exist unless the corresponding search tool executed successfully and returned an empty result.\n");
        prompt.append("Workflow contract:\n");
        prompt.append("- Treat every succeeded tool step with returned data as evidence, even when the model review marked it incomplete or partial.\n");
        prompt.append("- Treat commandContext as the authoritative description and execution-lineage map for a template result. Use commands[].description/analysisHint to interpret the purpose of canonical data at commands[].resultReference. Use references only for execution order or dependency; never treat a command description as an observed value or a command dependency as a business-data relationship.\n");
        prompt.append("- A metadata-only execution step with resultSetReference/bodyReference is not missing evidence. Follow the reference to the canonical data object and summarize that object once. Never duplicate the same result under both the execution step and result set.\n");
        prompt.append("- If any MCP tool returned non-empty data, you MUST analyze the supported parts of the user's task. Evidence gaps may reduce confidence and require a limitations section, but MUST NOT produce a refusal or an empty answer.\n");
        prompt.append("- When multiple plan attempts were executed, reconcile and summarize evidence from all attempts; prefer the latest complete result when evidence conflicts.\n");
        prompt.append("- Treat interpretation_evidence_iteration_v1 snapshots as the evidence chain: preserve their evidenceId-to-conclusion basis, missingEvidence, conflicts, and nextActions.\n");
        prompt.append("- Treat hypotheses as testable explanations. Explain which hypothesis is supported, contradicted, or unresolved and bind every claim to supportEvidenceIds or contradictEvidenceIds.\n");
        prompt.append("- A hypothesis statement is never evidence by itself. Its confidence and status must be justified by persisted Evidence Objects.\n");
        prompt.append("- Use hypothesis_tree_v1 parent/child relationships to explain broad conclusions through independently validated sub-hypotheses without flattening unresolved children into a supported parent.\n");
        prompt.append("- Use evidence_quality_v1 as independent quality dimensions. Each dimension has value/status/type/reason; UNKNOWN means not assessed and must never be interpreted as 0.5. modelConfidence is MODEL_ESTIMATED and is not an evidence-quality score.\n");
        prompt.append("- Use evidence_graph_v1 as the authoritative Evidence-to-Hypothesis relationship layer. Only ACTIVE SUPPORTS or CONTRADICTS relations with existing Evidence nodes may justify a hypothesis; rejectedRelations are audit findings, not evidence.\n");
        prompt.append("- Use plan_evolution_v1 only to explain why the runtime changed its retrieval or execution path. A plan change is not evidence for the user's factual answer.\n");
        prompt.append("- Treat template_selection_feedback.v1 and runtimeTemplateCandidateEvaluations as the decision ledger for template changes. When a previously preferred or more precise template was rejected, state its evidence-backed rejection reason; when another template was selected or queried, state the evidence gap it was chosen to close. Do not imply that a service was unavailable when the trace shows a binding, contract, parameter-evidence, policy, or pre-invocation failure.\n");
        prompt.append("- The final answer must be grounded in the cumulative MCP results from every iteration. Do not treat an intermediate model conclusion as evidence unless its referenced evidenceId exists in an executed tool result.\n");
        prompt.append("- System instructions and conversation history provide behavior and context only. A documentId, timestamp, metric, command output, or concrete result appearing there is not current-turn evidence unless the executed attempts below return the same value.\n");
        prompt.append("- Resolve conflicts explicitly. If three iterations still leave a material gap, report that gap instead of filling it with model knowledge.\n");
        prompt.append("- Do not hide earlier partial or failed attempts when they contain usable evidence. State unresolved limitations after considering all attempts.\n");
        prompt.append("- When diagnosticRun is present, report required/completed/failed/missing counts and the coverage ratio. List missing checks with their exact runtime reason.\n");
        prompt.append("- A missing diagnostic child with no ToolCallResult is NOT_EXECUTED. Do not speculate that it timed out, hit resource contention, lacked permissions, or failed remotely unless a child result explicitly records that status/reason.\n");
        prompt.append("- Do not recommend manual one-by-one execution as the product solution when an ordered runtime batch is expected. Report the missing batch dispatch/evidence and recommend repairing or retrying the batch workflow.\n");
        prompt.append("- For batch_execution_evidence.v1, results[].dataset.representativeRows and numericProfiles are authoritative returned business data. Analyze and report their concrete values. recordCount and omittedRecordCount describe model projection coverage; omitted rows remain in the tool trace. numericProfiles are mechanical statistics: use sum only when the field semantics prove additivity, and never sum identifiers, dates, prices, rates, or categorical codes merely because they are numeric. Never reduce a successful non-empty batch to execution metadata or claim that concrete values are unavailable when these dataset fields are present.\n");
        prompt.append(analysisSummaryGovernanceBridge.finalSynthesisInstruction());
        prompt.append("- Mandatory analysis deliverable: tables and returned rows are evidence attachments, not the summary itself. For every non-empty structured dataset, write a business-readable analysis paragraph using its governed analysisContext. State the dataset identity and purpose, explain material values/differences/anomalies supported by the rows, and relate datasets only when relationships are explicitly supplied. A heading, data-source label, row count, or table without analytical findings is incomplete.\n");
        prompt.append("- A successful template inventory is not a business result. When returned rows exist, do not replace them with phrases such as '可返回', '可获取', '可计算', template capability descriptions, or execution-count tables. Present the returned values first; execution metadata is secondary.\n");
        prompt.append("- diagnosticRun assessment scores are authoritative only when non-null. Never convert tool success, OPEN/running state, capacity size, or coverage ratio into a missing health score.\n");
        prompt.append("- Keep execution coverage and evidence quality separate. A successful query with incomplete requiredMetrics remains executed and covered, but its health assessment capability is LIMITED; never reduce coverage merely because quality is incomplete.\n");
        prompt.append("- Respect diagnostic_evidence_quality_v1 purpose and healthCapability. Inventory evidence may be displayed but must not be presented as a complete health assessment.\n");
        prompt.append("- Respect timeSemantics. SINCE_INSTANCE_START values are cumulative, not current pressure. When required context such as instance uptime or a sample window is missing, describe the historical cumulative observation and prohibit a real-time bottleneck conclusion.\n");
        prompt.append("- For point-in-time session and lock evidence, use sampling language: say 'at the current sample' or 'within the current sampling window'; never turn one sample into a historical trend claim.\n");
        prompt.append("- diagnosticRun.confidence_engine is the authoritative evidence-coverage classification. When partial_conclusion_allowed=true, provide a bounded partial diagnosis from completed checks and separately list what the missing checks prevent you from concluding.\n");
        prompt.append("- assessment.overall_status=INSUFFICIENT_EVIDENCE means no complete health score is available; it does not erase completed check evidence and must not force the entire report to say that nothing can be assessed.\n");
        prompt.append("- Never turn successful execution alone into a healthy finding. State metric conclusions only from returned values, and do not infer that an environment has no serious anomaly merely because queries succeeded.\n");
        prompt.append("- Use each step's review reason only to understand later evidence collection; it is not a substitute for returned evidence.\n");
        prompt.append("- Reconcile steps internally, but write the user-facing answer by finding or requested dimension rather than by execution order.\n");
        prompt.append("- If a succeeded SQL/database step is partial, still summarize the returned rows/metrics and explicitly state missing fields or limitations.\n");
        prompt.append("- If some step truly failed with no usable output, state the limitation and do not use that failed result as evidence.\n");
        prompt.append("- Do not display SQL statements, scripts, or query text that were executed by tools. Mention only the template/tool and returned data for executed operations.\n");
        prompt.append("- Do not present illustrative, manual, or 'typical' SQL/commands as executed, authorized, retrieved, or factual tool output when the authorized template evidence did not return their exact text. For an unexecuted check, report only capability, template identifier, status, and exact runtime reason.\n");
        prompt.append("- If the user explicitly asks to generate a SQL/DDL/command draft, provide a clearly labeled non-executed draft for human review instead of refusing solely because execution is not authorized. Separate observed facts from assumptions, and state that Runtime did not execute or approve the statement.\n");
        prompt.append("- Describe the number of plan attempts only from the Executed plan attempts count below. Distinguish BLOCKED before invocation from an actual remote tool execution; do not call a blocked step a completed diagnostic execution.\n");
        prompt.append("- Preserve canonical asset fields exactly: assets[].asset.displayName/name is the asset label, assets[].asset.id/assetId is the asset identifier, and assets[].asset.toolName is the bound tool. Never present toolName as displayName.\n");
        prompt.append("- Tool evidence may contain a tool_result_summary.v1 wrapper with summaryTruncated=true. That flag means only the inline preview was shortened; it is never evidence that a source field is absent. Do not claim a field is missing from a truncated preview unless Runtime emitted an authoritative contract violation after applying routingProjection and Agent context.\n");
        prompt.append("- The Agent runtime environment is authoritative for MCP routing. If a structured DAG repair records AGENT_ENVIRONMENT_CONTEXT_APPLIED, treat the environment edge/binding as satisfied and never report an asset-index environment defect.\n");
        prompt.append("- Do not invent facts that are not present in the step outputs or observations.\n");
        prompt.append("- For SQL metadata discovery, cite every recommended table by the exact physical identifier returned by the tool (database/schema/tableName when available). Keep any Chinese business description separate; never use it as a substitute for the physical table name.\n");
        prompt.append("- When returned metadata includes columns, list the exact physical column names under their corresponding physical table and preserve returned type/key/comment details. Never translate, rename, or invent identifiers.\n");
        prompt.append("- If column metadata was not returned for a matched table, say so explicitly instead of presenting illustrative fields as facts.\n\n");
        prompt.append("- For enterprise_metadata_model_context.v1, coverage.inputFieldCount, processedFieldCount, allFieldsProcessed, and fieldsWithCandidates are authoritative. Do not infer that unshown or unprocessed fields were matched. A field may be processed with zero candidates; only returned candidates may support its annotation.\n");
        prompt.append("- For enterprise_metadata_model_context.v1 and enterprise_metadata_discovery_context.v1, evidenceCoverage is descriptive only: it identifies returned field-standard reference data and never pre-decides whether a table or design conforms. The model must form conclusions from the actual target facts, returned standard references, and the user's requested evaluation criteria.\n");
        prompt.append("- Review notes and shortened previews are not factual evidence. When they conflict with authoritativeToolResultEvidence, use authoritativeToolResultEvidence and omit the conflicting review claim.\n\n");
        prompt.append("- Evidence-insufficient deliverable contract: state the incomplete assessment once and briefly, then present usable returned evidence, clearly labeled evidence-backed inferences or review checkpoints, and the smallest concrete evidence-recovery action. Do not turn the final answer into a tool trace or repeat 'cannot determine' for every requested dimension.\n");
        prompt.append("- Standard/reference evidence may support a clearly labeled possible business interpretation and conditional design checkpoints, but it must never be presented as the target object's observed schema or as a confirmed defect. Separate Observed facts, Inference/checkpoints, and Missing evidence.\n");
        if (compressionEnabled) {
            prompt.append("- Context compression is active because cumulative evidence exceeded the final-synthesis quality budget. Compressed evidence is a semantic projection of authoritative Runtime records, not proof that omitted details were absent.\n");
        }
        prompt.append("- Mandatory workflow observations are executed after the listed plan attempts. A successful local contract review in those observations is newer authoritative evidence and resolves earlier missing_evidence claims for the same tool result.\n");
        prompt.append("- Database layering labels (for example ADS/DWS/DWD/DIM), table names, schemas, databases, and fields are evidence facts only when the current tool output explicitly returned them. Never infer a layer from a naming convention. Never output 'possible table examples', 'common tables', or supplemental table recommendations that were not retrieved.\n");
        prompt.append(AgentRuntimeFactGroundingContract.promptSection());
        prompt.append("User query:\n").append(query == null ? "" : query).append("\n\n");
        prompt.append("context_compression: ")
            .append(Map.of(
                "enabled", compressionEnabled,
                "availableEvidenceTokens", summaryEvidenceBudget,
                "evidenceTokens", evidenceSize.tokens(),
                "evidenceChars", evidenceSize.chars()
            ))
            .append("\n\n");
        if (result != null && result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            prompt.append("Plan final answer hint, not authoritative evidence:\n")
                .append(result.finalAnswer())
                .append("\n\n");
        }
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
                Object diagnosticRun = attemptResult.metadata() == null
                    ? null
                    : attemptResult.metadata().get("diagnosticRun");
                if (diagnosticRun != null) {
                    prompt.append("  diagnosticRun (runtime-calculated coverage and explicit-evidence assessment): ")
                        .append(shortObservationText(stringify(diagnosticRun), 6000))
                        .append("\n");
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
                if (compressionEnabled) {
                    prompt.append("  compressedEvidence (runtime semantic projection):\n")
                        .append(stringify(dagDecisionModelOutputSnapshot(
                            step, executionCount, summaryEvidenceBudget)))
                        .append("\n");
                } else {
                    Map<String, Object> outputFacts = structuredOutputFacts(step.output());
                    if (!outputFacts.isEmpty()) {
                        prompt.append("  outputFacts: ")
                            .append(shortObservationText(stringify(outputFacts), 1800))
                            .append("\n");
                    }
                    Map<String, Object> rawOutputMap = objectMap(step.output());
                    if (rawOutputMap.get("results") instanceof List<?>) {
                        prompt.append("  batchChildEvidence (complete tool-returned structure):\n")
                            .append(stringify(step.output()))
                            .append("\n");
                    }
                    String executionEvidence = step.success()
                        ? toolObservationBuilder.buildAuthoritativeExecutionEvidence(step.toolName(), step.output())
                        : null;
                    if (executionEvidence != null && !executionEvidence.isBlank()) {
                        prompt.append("  authoritativeToolResultEvidence (runtime evidence projection; operation inputs omitted):\n")
                            .append(executionEvidence)
                            .append("\n  promptPreviewTruncated=false\n");
                    } else {
                        String serializedOutput = stringify(redactExecutionStatementText(step.output()));
                        prompt.append("  toolResult (complete Runtime input): ")
                            .append(serializedOutput)
                            .append("\n");
                    }
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
                    .append(", content=").append(compressionEnabled
                        ? shortObservationText(observation.content(), SUMMARY_COMPRESSED_OBSERVATION_CHARS)
                        : observation.content())
                    .append("\n");
                if (observation.metadata() != null && !observation.metadata().isEmpty()) {
                    prompt.append("  metadata: ")
                        .append(stringify(summaryObservationMetadata(observation.metadata())))
                        .append("\n");
                }
            }
        }
        if (observations != null && !observations.isEmpty()) {
            prompt.append("\nIn-memory observations:\n");
            observations.forEach(observation -> prompt.append("- ")
                .append(compressionEnabled
                    ? shortObservationText(observation, SUMMARY_COMPRESSED_OBSERVATION_CHARS)
                    : observation)
                .append("\n"));
        }
        prompt.append("\nReturn only the final user-facing Markdown answer, no JSON.");
        return prompt.toString();
    }

    private String buildStructuralFallbackSummaryPrompt(
        String query,
        String systemPrompt,
        InterpretationPlanRuntime.ExecutionResult result,
        List<InterpretationPlanRuntime.ExecutionResult> attemptResults
    ) {
        List<InterpretationPlanRuntime.ExecutionResult> results = attemptResults == null
            || attemptResults.isEmpty()
            ? (result == null ? List.of() : List.of(result))
            : attemptResults;
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are the final answer synthesizer for a partially successful Agent run.\n")
            .append("The persisted result-handling policy is SUMMARIZE_AVAILABLE. Keep the run presentable.\n")
            .append("Summarize every successful returned result with concrete values. Isolate failed or blocked children ")
            .append("and state their exact returned reasons. Never discard successful siblings because another child failed.\n")
            .append("Use only the structural Runtime evidence below. Return Chinese Markdown, not JSON.\n\n")
            .append("User query:\n").append(query == null ? "" : query).append("\n\n")
            .append("Structural Runtime evidence:\n");
        for (int attemptIndex = 0; attemptIndex < results.size(); attemptIndex++) {
            InterpretationPlanRuntime.ExecutionResult attempt = results.get(attemptIndex);
            if (attempt == null || attempt.steps() == null) {
                continue;
            }
            prompt.append("Attempt ").append(attemptIndex + 1).append(":\n");
            for (InterpretationPlanRuntime.StepExecution step : attempt.steps()) {
                prompt.append("- step=").append(step.stepId())
                    .append(", tool=").append(firstNonBlank(step.toolName(), step.actionType()))
                    .append(", success=").append(step.success()).append("\n");
                if (step.output() instanceof ToolCallBatchResult batch) {
                    prompt.append("  batchStatus=").append(batch.status()).append("\n");
                    for (ToolCallResult child : batch.results()) {
                        prompt.append("  childEvidence: ")
                            .append(shortObservationText(stringify(
                                contextEvidenceAggregator.aggregate(child)), 12_000))
                            .append("\n");
                    }
                } else {
                    prompt.append("  evidence: ")
                        .append(shortObservationText(stringify(
                            contextEvidenceAggregator.aggregate(step.output())), 12_000))
                        .append("\n");
                }
                if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
                    prompt.append("  error: ").append(step.errorMessage()).append("\n");
                }
            }
        }
        prompt.append("\nReturn only the final user-facing Markdown answer, no JSON.");
        return prompt.toString();
    }

    private boolean summarizeAvailableResults(Map<String, Object> runtimeAttributes) {
        if (runtimeAttributes == null) {
            return true;
        }
        Map<String, Object> policy = objectMap(runtimeAttributes.get("resultHandlingPolicy"));
        if (policy.isEmpty()) {
            return true;
        }
        Object continueOnPartial = policy.containsKey("continueOnPartialSuccess")
            ? policy.get("continueOnPartialSuccess") : policy.get("continue_on_partial_success");
        Object failOnChild = policy.containsKey("failRunWhenAnyChildFails")
            ? policy.get("failRunWhenAnyChildFails") : policy.get("fail_run_when_any_child_fails");
        return !Boolean.TRUE.equals(failOnChild)
            && !"true".equalsIgnoreCase(String.valueOf(failOnChild))
            && (continueOnPartial == null
                || Boolean.TRUE.equals(continueOnPartial)
                || "true".equalsIgnoreCase(String.valueOf(continueOnPartial)));
    }

    RecordCoverageBundle buildRecordCoverageBundle(
        ChatModel activeChatModel,
        String query,
        InterpretationPlanRuntime.ExecutionResult result,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata,
        BooleanSupplier cancellationCheck
    ) {
        GovernanceIsolationScope isolationScope = governanceIsolationScope(metadata, runtimeAttributes);
        List<BatchRecordSet> recordSets = executionRecordSets(result);
        if (recordSets.isEmpty()) {
            return RecordCoverageBundle.empty();
        }
        StringBuilder promptEvidence = new StringBuilder(
            "Complete returned-record evidence (record_grounded_analysis.v1). "
                + "Every range below is processed evidence; final analysis must use it and must not substitute execution metadata.\n");
        StringBuilder appendix = new StringBuilder();
        List<List<String>> recordValueGroups = new ArrayList<>();
        int returnedRecordCount = 0;
        int processedRecordCount = 0;
        int iterations = 0;
        boolean iterative = false;
        boolean sourceContentComplete = true;
        List<AnalysisSummaryResult> governedSummaryResults = new ArrayList<>();
        for (BatchRecordSet recordSet : recordSets) {
            returnedRecordCount += recordSet.records().size();
            sourceContentComplete &= recordSet.records().stream()
                .noneMatch(record -> Boolean.FALSE.equals(record.get("sourceComplete")));
            recordSet.records().forEach(record ->
                recordValueGroups.add(recordValueGroup(record, query)));
            String allRecords = stringify(recordSet.records());
            boolean oversized = allRecords.length() > RECORD_ANALYSIS_CHUNK_MAX_CHARS;
            iterative |= oversized;
            List<List<Map<String, Object>>> chunks = oversized
                ? recordChunks(recordSet.records()) : List.of(recordSet.records());
            Map<String, Object> governedContext = analysisSummaryGovernanceBridge.govern(
                recordSet.reference(), recordSet.analysisContext(), recordSet.records());
            promptEvidence.append("- ").append(recordSet.reference())
                .append(" analysisContext: ").append(stringify(governedContext)).append("\n");
            appendix.append("### ").append(recordSet.reference()).append("\n\n");
            int from = 1;
            for (int chunkOffset = 0; chunkOffset < chunks.size(); chunkOffset++) {
                List<Map<String, Object>> chunk = chunks.get(chunkOffset);
                runtimeGuard.checkCancelled(cancellationCheck);
                int to = from + chunk.size() - 1;
                iterations++;
                AnalysisSummaryGovernanceBridge.ChunkPosition position =
                    analysisSummaryGovernanceBridge.position(recordSet.reference(), chunkOffset + 1,
                        chunks.size(), from, to, recordSet.records().size());
                boolean governedModelSummaryRequired =
                    analysisSummaryGovernanceBridge.requiresModelSummary(governedContext, oversized);
                AnalysisSummaryResult governedSummary = governedModelSummaryRequired
                    ? analysisSummaryGovernanceBridge.summarize(
                        activeChatModel, isolationScope, position, governedContext, chunk, query)
                    : analysisSummaryGovernanceBridge.preserve(
                        isolationScope, position, governedContext, chunk);
                governedSummaryResults.add(governedSummary);
                runResultAdapter.recordRuntimeObservation(
                    runtimeAttributes,
                    AGENT_RUN_ID_ATTRIBUTE,
                    "Governed analysis chunk summary recorded for " + governedSummary.resultId() + ".",
                    "analysis_summary_governance",
                    metadataOf(
                        "type", "analysis_summary_chunk",
                        "analysisSummaryResult", governedSummary.toMap(),
                        "tenantId", isolationScope.tenantId(),
                        "runId", isolationScope.runId()
                    )
                );
                String analysis = governedSummary.content();
                if (metadata != null
                    && "STRUCTURED_RECORD_FALLBACK".equals(governedSummary.outcome())) {
                    metadata.put("recordAnalysisChunkFallback", true);
                }
                processedRecordCount += chunk.size();
                String range = "records[" + from + ".." + to + "]";
                promptEvidence.append("- ").append(recordSet.reference()).append(' ')
                    .append(range).append(" position=")
                    .append(ModelProtocolJson.compact(position.toMap()))
                    .append(": ").append(analysis).append("\n");
                appendix.append("- ").append(range).append("：")
                    .append(analysis).append("\n");
                from = to + 1;
            }
            appendix.append("\n");
        }
        boolean coverageComplete = processedRecordCount == returnedRecordCount;
        promptEvidence.append("Coverage: returnedRecordCount=").append(returnedRecordCount)
            .append(", processedRecordCount=").append(processedRecordCount)
            .append(", complete=").append(coverageComplete)
            .append(", sourceContentComplete=").append(sourceContentComplete).append(".\n");
        if (metadata != null) {
            metadata.put("recordAnalysisContractVersion", "record_grounded_analysis.v1");
            metadata.put("recordAnalysisReturnedRecordCount", returnedRecordCount);
            metadata.put("recordAnalysisProcessedRecordCount", processedRecordCount);
            metadata.put("recordAnalysisCoverageComplete", coverageComplete);
            metadata.put("recordAnalysisSourceContentComplete", sourceContentComplete);
            metadata.put("recordAnalysisIterationCount", iterations);
            metadata.put("recordAnalysisIterative", iterative);
            metadata.put("analysisSummaryGovernanceBridge",
                analysisSummaryGovernanceBridge.ledger(governedSummaryResults,
                    returnedRecordCount, processedRecordCount, coverageComplete));
        }
        return new RecordCoverageBundle(
            promptEvidence.toString(), appendix.toString(), List.copyOf(recordValueGroups),
            returnedRecordCount, processedRecordCount, iterations, iterative, coverageComplete,
            sourceContentComplete,
            List.copyOf(governedSummaryResults));
    }

    private List<BatchRecordSet> executionRecordSets(InterpretationPlanRuntime.ExecutionResult result) {
        if (result == null || result.steps() == null) {
            return List.of();
        }
        List<BatchRecordSet> sets = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            Object resolvedStepOutput = resolvedEvidenceData(step);
            if (resolvedStepOutput instanceof ToolCallBatchResult batch) {
                for (ToolCallResult child : batch.results()) {
                    if (!"SUCCESS".equalsIgnoreCase(child.status()) || !child.evidenceUsable()) {
                        continue;
                    }
                    String reference = firstNonBlank(child.templateId(),
                        firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "result")));
                    sets.addAll(outputRecordSets(child.output(), reference,
                        toolRegistry.getToolMetadata(child.toolName())));
                }
                continue;
            }
            if (step.success()) {
                Object evidenceOutput = resolvedStepOutput;
                sets.addAll(outputRecordSets(
                    evidenceOutput, firstNonBlank(step.toolName(), "step-" + step.stepId()),
                    toolRegistry.getToolMetadata(step.toolName())));
            }
        }
        return List.copyOf(sets);
    }

    private List<BatchRecordSet> outputRecordSets(Object output,
                                                  String reference,
                                                  ToolMetadata toolMetadata) {
        Map<String, Object> rootAnalysisContext =
            mcpAnalysisContextAdapter.adapt(reference, toolMetadata, output);
        List<BatchRecordSet> sqlSets = sqlRecordSets(output, reference, rootAnalysisContext);
        if (!sqlSets.isEmpty()) {
            return sqlSets;
        }
        List<BatchRecordSet> structuredSets = structuredDatasetRecordSets(
            output, reference, rootAnalysisContext);
        if (!structuredSets.isEmpty()) {
            return structuredSets;
        }
        List<Map<String, Object>> records = protocolRecords(output);
        if (!records.isEmpty()) {
            return List.of(new BatchRecordSet(reference, rootAnalysisContext, records));
        }
        List<BatchRecordSet> linuxSets = linuxStreamRecordSets(output, reference, rootAnalysisContext);
        if (!linuxSets.isEmpty()) {
            return linuxSets;
        }
        return externalizedPreviewRecordSets(output, reference, toolMetadata, rootAnalysisContext);
    }

    /**
     * Projects every governed structured dataset independently so iterative summaries retain
     * the dataset's own identity, field semantics, and relationships. This is schema-shaped and
     * intentionally independent of source type or business domain.
     */
    private List<BatchRecordSet> structuredDatasetRecordSets(Object output,
                                                             String reference,
                                                             Map<String, Object> rootAnalysisContext) {
        Map<String, Object> root = objectMap(output);
        List<Map<String, Object>> containers = new ArrayList<>();
        if (!root.isEmpty()) containers.add(root);
        for (String key : List.of("data", "result", "payload", "structuredContent", "structured_content")) {
            Map<String, Object> nested = objectMap(root.get(key));
            if (!nested.isEmpty()) containers.add(nested);
        }
        for (Map<String, Object> container : containers) {
            List<Map<String, Object>> datasets = objectMapList(container.get("structuredData"));
            if (datasets.isEmpty()) continue;
            List<BatchRecordSet> sets = new ArrayList<>();
            for (int index = 0; index < datasets.size(); index++) {
                Map<String, Object> dataset = datasets.get(index);
                List<Map<String, Object>> rows = firstNonEmptyRecordList(dataset, "records", "rows", "results");
                if (rows.isEmpty()) continue;
                String datasetReference = firstNonBlank(stringValue(firstNonNull(
                    dataset.get("dataset"), dataset.get("id"))), reference + "#dataset-" + (index + 1));
                sets.add(new BatchRecordSet(datasetReference,
                    mcpAnalysisContextAdapter.adaptDataset(rootAnalysisContext, dataset), rows));
            }
            if (!sets.isEmpty()) return List.copyOf(sets);
        }
        return List.of();
    }

    /**
     * Keeps a non-empty bounded preview as partial evidence when the Runtime-owned
     * full payload cannot be resolved. This is protocol-driven: it applies to every
     * externalized tool result and does not depend on command, template, or domain.
     */
    private List<BatchRecordSet> externalizedPreviewRecordSets(Object output,
                                                               String reference,
                                                               ToolMetadata toolMetadata,
                                                               Map<String, Object> rootAnalysisContext) {
        Map<String, Object> root = objectMap(output);
        if (!Boolean.TRUE.equals(booleanValue(root.get("outputTruncated")))) {
            return List.of();
        }
        Object preview = root.get("preview");
        if (preview == null || String.valueOf(preview).isBlank()) {
            return List.of();
        }
        Map<String, Object> structuredPreview = objectMap(preview);
        if (!structuredPreview.isEmpty() && structuredPreview != root) {
            List<BatchRecordSet> nested = outputRecordSets(
                structuredPreview, reference + "#externalized-preview", toolMetadata);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stream", "externalized-preview");
        record.put("sourceComplete", false);
        record.put("content", String.valueOf(preview));
        return List.of(new BatchRecordSet(
            reference + "#externalized-preview", rootAnalysisContext, List.of(Map.copyOf(record))));
    }

    private List<BatchRecordSet> sqlRecordSets(Object output,
                                               String reference,
                                               Map<String, Object> rootAnalysisContext) {
        Map<String, Object> root = objectMap(output);
        String dataSchema = stringValue(root.get("dataSchema"));
        Map<String, Object> data = objectMap(root.get("data"));
        if ("sql_result.v1".equals(dataSchema)) {
            List<Map<String, Object>> rows = objectMapList(data.get("rows"));
            if (rows.isEmpty()) {
                return List.of();
            }
            boolean sourceComplete = !Boolean.TRUE.equals(booleanValue(data.get("possiblyTruncated")));
            List<Map<String, Object>> annotated = new ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> row = new LinkedHashMap<>(rows.get(index));
                row.put("_resultRowIndex", index + 1);
                row.put("sourceComplete", sourceComplete);
                annotated.add(Map.copyOf(row));
            }
            return List.of(new BatchRecordSet(reference, rootAnalysisContext, List.copyOf(annotated)));
        }
        boolean scriptResult = "sql_script_result.v1".equals(dataSchema);
        boolean multiQueryResult = "database_query_multi_sql_result.v1".equals(dataSchema)
            || "database_query_workflow_result.v1".equals(dataSchema);
        if (!scriptResult && !multiQueryResult) {
            return List.of();
        }
        List<BatchRecordSet> sets = new ArrayList<>();
        List<Map<String, Object>> resultSets = objectMapList(
            firstNonNull(data.get("results"), data.get("resultSets")));
        for (Map<String, Object> resultSet : resultSets) {
            List<Map<String, Object>> rows = objectMapList(resultSet.get("rows"));
            if (rows.isEmpty()) {
                continue;
            }
            boolean sourceComplete = !Boolean.TRUE.equals(booleanValue(resultSet.get("possiblyTruncated")));
            List<Map<String, Object>> annotated = new ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> row = new LinkedHashMap<>(rows.get(index));
                Object statementIndex = firstNonNull(
                    resultSet.get("statementIndex"), resultSet.get("executionOrder"));
                Object stepCode = firstNonNull(resultSet.get("stepCode"), resultSet.get("sqlCode"));
                if (statementIndex != null) {
                    row.put("_statementIndex", statementIndex);
                }
                if (stepCode != null) {
                    row.put("_stepCode", stepCode);
                }
                row.put("_resultRowIndex", index + 1);
                row.put("sourceComplete", sourceComplete);
                annotated.add(Map.copyOf(row));
            }
            Map<String, Object> resultSetAnalysisContext =
                mcpAnalysisContextAdapter.adaptDataset(rootAnalysisContext, resultSet);
            sets.add(new BatchRecordSet(
                reference + "#statement-" + firstNonBlank(stringValue(firstNonNull(
                    resultSet.get("statementIndex"), resultSet.get("executionOrder"))), "?"),
                resultSetAnalysisContext,
                List.copyOf(annotated)));
        }
        return List.copyOf(sets);
    }

    private Object resolvedEvidenceData(InterpretationPlanRuntime.StepExecution step) {
        if (step == null) {
            return null;
        }
        Object data = step.output();
        if (data instanceof ToolCallBatchResult batch) {
            return toolRuntimeService.resolveBatchOutputForEvidenceReview(batch);
        }
        return step.toolExecution() == null
            ? data
            : toolRuntimeService.resolveOutputForEvidenceReview(step.toolExecution().output());
    }

    private List<BatchRecordSet> linuxStreamRecordSets(Object output,
                                                       String reference,
                                                       Map<String, Object> rootAnalysisContext) {
        Map<String, Object> root = objectMap(output);
        if (!"ssh_steps.v1".equals(stringValue(root.get("dataSchema")))) {
            return List.of();
        }
        Map<String, Object> data = objectMap(root.get("data"));
        Map<String, Object> limits = objectMap(data.get("outputLimits"));
        List<BatchRecordSet> sets = new ArrayList<>();
        appendLinuxStreamRecordSet(sets, reference, "stdout", stringValue(data.get("stdout")),
            !Boolean.parseBoolean(stringValue(limits.get("stdoutTruncated"))), rootAnalysisContext);
        appendLinuxStreamRecordSet(sets, reference, "stderr", stringValue(data.get("stderr")),
            !Boolean.parseBoolean(stringValue(limits.get("stderrTruncated"))), rootAnalysisContext);
        return List.copyOf(sets);
    }

    private void appendLinuxStreamRecordSet(List<BatchRecordSet> target,
                                            String reference,
                                            String stream,
                                            String text,
                                            boolean sourceComplete,
                                            Map<String, Object> rootAnalysisContext) {
        if (text == null || text.isBlank()) {
            return;
        }
        int chunkChars = Math.max(1_000, RECORD_ANALYSIS_CHUNK_MAX_CHARS - 2_000);
        List<Map<String, Object>> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (int from = 0; from < text.length(); from += chunkChars) {
            int to = Math.min(text.length(), from + chunkChars);
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("stream", stream);
            chunk.put("chunkIndex", ++chunkIndex);
            chunk.put("fromChar", from);
            chunk.put("toChar", to);
            chunk.put("sourceComplete", sourceComplete);
            chunk.put("content", text.substring(from, to));
            chunks.add(Map.copyOf(chunk));
        }
        target.add(new BatchRecordSet(
            reference + "#" + stream,
            rootAnalysisContext == null ? Map.of() : rootAnalysisContext,
            List.copyOf(chunks)));
    }

    private List<Map<String, Object>> protocolRecords(Object output) {
        Map<String, Object> root = objectMap(output);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> records = firstNonEmptyRecordList(root, "records", "rows", "results");
        if (!records.isEmpty()) {
            return records;
        }
        Map<String, Object> data = objectMap(root.get("data"));
        records = firstNonEmptyRecordList(data, "records", "rows", "results");
        if (!records.isEmpty()) {
            return records;
        }
        Map<String, Object> body = objectMap(data.get("body"));
        records = firstNonEmptyRecordList(body, "records", "rows", "results");
        if (!records.isEmpty()) {
            return records;
        }
        records = objectMapList(data.get("body"));
        if (!records.isEmpty()) {
            return records;
        }
        Map<String, Object> result = objectMap(root.get("result"));
        return firstNonEmptyRecordList(result, "records", "rows", "results");
    }

    private List<Map<String, Object>> firstNonEmptyRecordList(
        Map<String, Object> source,
        String... keys
    ) {
        for (String key : keys) {
            List<Map<String, Object>> records = objectMapList(source.get(key));
            if (!records.isEmpty()) {
                return records;
            }
        }
        return List.of();
    }

    private List<List<Map<String, Object>>> recordChunks(List<Map<String, Object>> records) {
        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        int currentChars = 0;
        for (Map<String, Object> record : records) {
            int recordChars = Math.max(1, stringify(record).length());
            if (!current.isEmpty() && (current.size() >= RECORD_ANALYSIS_CHUNK_MAX_ROWS
                || currentChars + recordChars > RECORD_ANALYSIS_CHUNK_MAX_CHARS)) {
                chunks.add(List.copyOf(current));
                current.clear();
                currentChars = 0;
            }
            current.add(record);
            currentChars += recordChars;
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }
        return List.copyOf(chunks);
    }

    private List<String> recordValueGroup(Map<String, Object> record, String query) {
        String normalizedQuery = firstNonBlank(query, "").replace(",", "");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object rawValue : record.values()) {
            if (rawValue instanceof Map<?, ?> || rawValue instanceof Iterable<?> || rawValue == null) {
                continue;
            }
            String value = String.valueOf(rawValue).trim();
            String comparable = value.replace(",", "");
            if (value.length() >= 3 && !normalizedQuery.contains(comparable)) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            values.add(ModelProtocolJson.compact(record));
        }
        return List.copyOf(values);
    }

    String ensureCompleteRecordCoveragePresented(
        String answer,
        RecordCoverageBundle coverage,
        Map<String, Object> metadata
    ) {
        if (coverage.returnedRecordCount() == 0) {
            return answer;
        }
        String governedAnswer = ensureGovernedNarrativeAnalysis(answer, coverage, metadata);
        boolean everyRecordReferenced = coverage.recordValueGroups().stream()
            .allMatch(values -> containsAnyConcreteValue(governedAnswer, values));
        if (everyRecordReferenced && !coverage.iterative() && coverage.sourceContentComplete()) {
            return governedAnswer;
        }
        long governedSummaryCount = coverage.summaryResults().stream()
            .filter(summary -> "MODEL_SUMMARY".equals(summary.outcome()))
            .filter(summary -> summary.content() != null && !summary.content().isBlank())
            .count();
        if (coverage.coverageComplete() && coverage.sourceContentComplete()
            && governedSummaryCount > 0) {
            if (metadata != null) {
                metadata.put("recordAnalysisCoverageAppendixApplied", false);
                metadata.put("recordAnalysisNarrativeCoverageApplied", true);
                metadata.put("recordAnalysisEveryRecordReferencedByModel", everyRecordReferenced);
                metadata.put("recordAnalysisGovernedSummaryCount", governedSummaryCount);
            }
            return firstNonBlank(governedAnswer, "")
                + "\n\n> 证据覆盖：已处理 " + coverage.processedRecordCount() + "/"
                + coverage.returnedRecordCount() + " 条返回记录，共形成 "
                + governedSummaryCount + " 个受治理证据摘要；该说明仅表示覆盖范围，不构成新增结论。";
        }
        if (metadata != null) {
            metadata.put("recordAnalysisCoverageAppendixApplied", true);
            metadata.put("recordAnalysisEveryRecordReferencedByModel", everyRecordReferenced);
        }
        return firstNonBlank(governedAnswer, "")
            + "\n\n## \u5168\u91cf\u8bb0\u5f55\u8986\u76d6\u5206\u6790\n\n"
            + coverage.appendix()
            + "\n\u8986\u76d6\u6821\u9a8c\uff1a"
            + coverage.processedRecordCount() + "/" + coverage.returnedRecordCount()
            + (!coverage.coverageComplete()
                ? "\uff08\u672a\u5b8c\u6574\uff09"
                : coverage.sourceContentComplete()
                    ? "\uff08\u5b8c\u6574\uff09"
                    : "\uff08\u5df2\u5b8c\u6574\u5904\u7406\u8fd4\u56de\u9884\u89c8\uff0c\u6e90\u5185\u5bb9\u4e0d\u5b8c\u6574\uff09");
    }

    private String ensureGovernedNarrativeAnalysis(String answer,
                                                   RecordCoverageBundle coverage,
                                                   Map<String, Object> metadata) {
        if (hasNarrativeAnalysis(answer)) {
            return answer;
        }
        List<AnalysisSummaryResult> modelSummaries = coverage.summaryResults().stream()
            .filter(summary -> "MODEL_SUMMARY".equals(summary.outcome()))
            .filter(summary -> summary.content() != null && !summary.content().isBlank())
            .toList();
        if (modelSummaries.isEmpty()) {
            if (metadata != null) metadata.put("governedNarrativeAnalysisUnavailable", true);
            return answer;
        }
        StringBuilder appendix = new StringBuilder(firstNonBlank(answer, ""));
        appendix.append("\n\n## 数据分析总结\n\n");
        for (AnalysisSummaryResult summary : modelSummaries) {
            String dataset = stringValue(summary.position().get("datasetReference"));
            String displayName = stringValue(objectMap(summary.analysisContext().get("source")).get("displayName"));
            if (modelSummaries.size() > 1 || (dataset != null && !dataset.isBlank())) {
                appendix.append("### ").append(firstNonBlank(displayName,
                    firstNonBlank(dataset, "数据集分析"))).append("\n\n");
            }
            appendix.append(summary.content().trim()).append("\n\n");
        }
        if (metadata != null) {
            metadata.put("governedNarrativeAnalysisAppended", true);
            metadata.put("governedNarrativeAnalysisSummaryCount", modelSummaries.size());
        }
        return appendix.toString().trim();
    }

    private boolean hasNarrativeAnalysis(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String narrative = java.util.Arrays.stream(answer.split("\\R"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !line.startsWith("#") && !line.startsWith("|") && !line.startsWith("```"))
            .filter(line -> !line.matches("^[-:| ]+$"))
            .filter(line -> !line.matches("^(?:[-*]\\s*)?(?:数据来源|来源|数据集|共?\\s*\\d+\\s*(?:行|个数据集)).*$"))
            .map(line -> line.replaceAll("[`*_>#]", "").trim())
            .collect(java.util.stream.Collectors.joining(" "));
        return narrative.length() >= 40;
    }

    private record BatchRecordSet(String reference,
                                  Map<String, Object> analysisContext,
                                  List<Map<String, Object>> records) {
        private BatchRecordSet(String reference, List<Map<String, Object>> records) {
            this(reference, Map.of(), records);
        }

        private BatchRecordSet {
            analysisContext = analysisContext == null ? Map.of() : Map.copyOf(analysisContext);
        }
    }

    record RecordCoverageBundle(
        String promptEvidence,
        String appendix,
        List<List<String>> recordValueGroups,
        int returnedRecordCount,
        int processedRecordCount,
        int iterations,
        boolean iterative,
        boolean coverageComplete,
        boolean sourceContentComplete,
        List<AnalysisSummaryResult> summaryResults
    ) {
        private static RecordCoverageBundle empty() {
            return new RecordCoverageBundle("", "", List.of(), 0, 0, 0, false, true, true, List.of());
        }
    }

    String buildDeterministicAvailableResultAnswer(
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        StringBuilder answer = new StringBuilder("## \u53ef\u7528\u6267\u884c\u7ed3\u679c\n\n");
        int successfulChildren = 0;
        int failedChildren = 0;
        if (result != null && result.steps() != null) {
            for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
                if (!(step.output() instanceof ToolCallBatchResult batch)) {
                    continue;
                }
                for (ToolCallResult child : batch.results()) {
                    String title = firstNonBlank(child.templateId(),
                        firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "\u7ed3\u679c")));
                    answer.append("### ").append(title).append("\n\n");
                    if ("SUCCESS".equalsIgnoreCase(child.status()) && child.evidenceUsable()) {
                        successfulChildren++;
                        answer.append("- \u72b6\u6001\uff1a\u6210\u529f\n");
                        Map<String, Object> output = objectMap(child.output());
                        Map<String, Object> data = objectMap(output.get("data"));
                        Map<String, Object> body = objectMap(data.get("body"));
                        List<Map<String, Object>> records = objectMapList(body.get("records"));
                        if (!records.isEmpty()) {
                            answer.append("- \u8fd4\u56de\u8bb0\u5f55\u6570\uff1a").append(records.size()).append("\n")
                                .append("- \u4ee3\u8868\u8bb0\u5f55\uff1a`")
                                .append(shortObservationText(stringify(records.get(0)), 2_000))
                                .append("`\n");
                        } else {
                            answer.append("- \u8fd4\u56de\u5185\u5bb9\uff1a`")
                                .append(shortObservationText(stringify(
                                    contextEvidenceAggregator.aggregate(child.output())), 2_000))
                                .append("`\n");
                        }
                    } else {
                        failedChildren++;
                        answer.append("- \u72b6\u6001\uff1a")
                            .append(firstNonBlank(child.status(), "FAILED"));
                        if (child.error() != null && !child.error().isEmpty()) {
                            answer.append("\n- \u539f\u56e0\uff1a`")
                                .append(shortObservationText(stringify(child.error()), 1_000))
                                .append("`");
                        }
                        answer.append("\n");
                    }
                    answer.append("\n");
                }
            }
        }
        if (successfulChildren == 0 && failedChildren == 0) {
            answer.append("\u672c\u6b21\u6ca1\u6709\u53ef\u5c55\u793a\u7684\u6279\u91cf\u5b50\u7ed3\u679c\u3002\n\n");
        }
        answer.append("---\n")
            .append("\u6210\u529f\u5b50\u9879\uff1a").append(successfulChildren)
            .append("\uff1b\u672a\u6210\u529f\u5b50\u9879\uff1a").append(failedChildren)
            .append("\u3002\u4ee5\u4e0a\u5185\u5bb9\u7531 Runtime \u6839\u636e\u5df2\u8fd4\u56de\u8bc1\u636e\u751f\u6210\u3002\n");
        return answer.toString();
    }

    private String buildLegacyDeterministicAvailableResultAnswer(
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        StringBuilder answer = new StringBuilder("## 可用执行结果\n\n");
        int successfulChildren = 0;
        int failedChildren = 0;
        if (result != null && result.steps() != null) {
            for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
                if (!(step.output() instanceof ToolCallBatchResult batch)) {
                    continue;
                }
                for (ToolCallResult child : batch.results()) {
                    if ("SUCCESS".equalsIgnoreCase(child.status()) && child.evidenceUsable()) {
                        successfulChildren++;
                        answer.append("### ")
                            .append(firstNonBlank(child.templateId(),
                                firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "结果"))))
                            .append("\n\n")
                            .append("- 状态：成功\n");
                        Map<String, Object> output = objectMap(child.output());
                        Map<String, Object> data = objectMap(output.get("data"));
                        Map<String, Object> body = objectMap(data.get("body"));
                        Object recordsValue = body.get("records");
                        if (recordsValue instanceof List<?> records) {
                            answer.append("- 返回记录数：").append(records.size()).append("\n");
                            if (!records.isEmpty()) {
                                answer.append("- 代表记录：`")
                                    .append(shortObservationText(stringify(records.get(0)), 2_000))
                                    .append("`\n");
                            }
                        } else {
                            answer.append("- 返回内容：`")
                                .append(shortObservationText(stringify(
                                    contextEvidenceAggregator.aggregate(child.output())), 2_000))
                                .append("`\n");
                        }
                        answer.append("\n");
                    } else {
                        failedChildren++;
                        answer.append("### ")
                            .append(firstNonBlank(child.templateId(),
                                firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "子任务"))))
                            .append("\n\n- 状态：")
                            .append(firstNonBlank(child.status(), "FAILED"));
                        if (child.error() != null && !child.error().isEmpty()) {
                            answer.append("\n- 原因：`")
                                .append(shortObservationText(stringify(child.error()), 1_000))
                                .append("`");
                        }
                        answer.append("\n\n");
                    }
                }
            }
        }
        if (successfulChildren == 0 && failedChildren == 0) {
            answer.append("本次没有可展示的批量子结果。\n");
        }
        answer.append("---\n")
            .append("成功子项：").append(successfulChildren)
            .append("；未成功子项：").append(failedChildren)
            .append("。最终模型整理失败，以上内容由 Runtime 直接根据已返回证据生成。\n");
        return answer.toString();
    }

    String ensureConcreteBatchEvidencePresented(
        String modelAnswer,
        String query,
        InterpretationPlanRuntime.ExecutionResult result,
        Map<String, Object> metadata
    ) {
        List<List<String>> concreteValueGroups = concreteBatchValueGroups(result, query);
        if (concreteValueGroups.isEmpty()
            || concreteValueGroups.stream().allMatch(values -> containsAnyConcreteValue(modelAnswer, values))) {
            return modelAnswer;
        }
        String deterministic = buildDeterministicAvailableResultAnswer(result);
        if (deterministic.isBlank()) {
            return modelAnswer;
        }
        if (metadata != null) {
            metadata.put("concreteBatchEvidencePresentationFallback", true);
            metadata.put("concreteBatchEvidenceChildCount", concreteValueGroups.size());
            metadata.put("concreteBatchEvidenceCandidateCount", concreteValueGroups.stream()
                .mapToInt(List::size).sum());
        }
        return firstNonBlank(modelAnswer, "")
            + "\n\n## \u5b9e\u9645\u8fd4\u56de\u6570\u636e\uff08Runtime \u6838\u9a8c\u8865\u5145\uff09\n\n"
            + deterministic;
    }

    private List<List<String>> concreteBatchValueGroups(
        InterpretationPlanRuntime.ExecutionResult result,
        String query
    ) {
        if (result == null || result.steps() == null) {
            return List.of();
        }
        String normalizedQuery = firstNonBlank(query, "").replace(",", "");
        List<List<String>> groups = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            if (!(step.output() instanceof ToolCallBatchResult batch)) {
                continue;
            }
            for (ToolCallResult child : batch.results()) {
                LinkedHashSet<String> values = new LinkedHashSet<>();
                Map<String, Object> output = asMap(child.output());
                Map<String, Object> data = asMap(output.get("data"));
                Map<String, Object> body = asMap(data.get("body"));
                for (Map<String, Object> record : objectMapList(body.get("records"))) {
                    for (Object rawValue : record.values()) {
                        if (rawValue instanceof Map<?, ?> || rawValue instanceof Iterable<?> || rawValue == null) {
                            continue;
                        }
                        String value = String.valueOf(rawValue).trim();
                        String comparable = value.replace(",", "");
                        if (value.length() >= 3
                            && !normalizedQuery.contains(comparable)
                            && !"0.00".equals(comparable)
                            && !"0.0".equals(comparable)) {
                            values.add(value);
                        }
                    }
                }
                if (!values.isEmpty()) {
                    groups.add(List.copyOf(values));
                }
            }
        }
        return List.copyOf(groups);
    }

    private boolean containsAnyConcreteValue(String answer, List<String> values) {
        String normalizedAnswer = firstNonBlank(answer, "").replace(",", "");
        return values.stream()
            .map(value -> value.replace(",", ""))
            .anyMatch(normalizedAnswer::contains);
    }

    private ContextTokenEstimator.Size estimateSummaryEvidenceSize(
        List<InterpretationPlanRuntime.ExecutionResult> results,
        List<String> observations,
        List<AgentObservation> storedObservations
    ) {
        ContextTokenEstimator.Size size = new ContextTokenEstimator.Size(0, 0);
        if (results != null) {
            for (InterpretationPlanRuntime.ExecutionResult attempt : results) {
                if (attempt == null) {
                    continue;
                }
                size = size.plus(contextTokenEstimator.estimate(attempt.metadata()))
                    .plus(contextTokenEstimator.estimate(attempt.finalAnswer()));
                if (attempt.steps() != null) {
                    for (InterpretationPlanRuntime.StepExecution step : attempt.steps()) {
                        if (step != null) {
                            size = size.plus(contextTokenEstimator.estimate(step.output()))
                                .plus(contextTokenEstimator.estimate(step.metadata()));
                        }
                    }
                }
            }
        }
        size = size.plus(contextTokenEstimator.estimate(observations));
        if (storedObservations != null) {
            for (AgentObservation observation : storedObservations) {
                if (observation != null) {
                    size = size.plus(contextTokenEstimator.estimate(observation.content()))
                        .plus(contextTokenEstimator.estimate(
                            summaryObservationMetadata(observation.metadata())));
                }
            }
        }
        return size;
    }

    private Object summaryObservationMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> auditMetadata = new LinkedHashMap<>(metadata);
        auditMetadata.remove("stepOutput");
        auditMetadata.put("stepOutputLocation", "executed plan attempt evidence above");
        return ToolLogSummarizer.summarize(
            auditMetadata,
            SUMMARY_OBSERVATION_METADATA_CHARS
        );
    }

    private ContextTokenEstimator.Size estimateDagDecisionEvidenceSize(
        InterpretationPlanRuntime.DagDecisionRequest request
    ) {
        ContextTokenEstimator.Size size = new ContextTokenEstimator.Size(0, 0);
        if (request == null || request.executions() == null) {
            return size;
        }
        for (InterpretationPlanRuntime.StepExecution execution : request.executions()) {
            if (execution == null) {
                continue;
            }
            size = size.plus(contextTokenEstimator.estimate(execution.output()))
                .plus(contextTokenEstimator.estimate(execution.metadata()));
        }
        return size;
    }

    private int dagDecisionEvidenceTokenBudget() {
        return Math.min(contextBudget.availableEvidenceTokens(), DAG_DECISION_EVIDENCE_TOKEN_BUDGET);
    }

    Map<String, Object> dagDecisionModelOutputSnapshot(InterpretationPlanRuntime.StepExecution execution) {
        return dagDecisionModelOutputSnapshot(execution, 1);
    }

    private Map<String, Object> dagDecisionModelOutputSnapshot(
        InterpretationPlanRuntime.StepExecution execution,
        int executionCount
    ) {
        return dagDecisionModelOutputSnapshot(
            execution, executionCount, contextBudget.availableEvidenceTokens());
    }

    private Map<String, Object> dagDecisionModelOutputSnapshot(
        InterpretationPlanRuntime.StepExecution execution,
        int executionCount,
        int totalEvidenceBudgetTokens
    ) {
        if (execution == null) {
            return Map.of();
        }
        int perEvidenceBudget = Math.max(1_000,
            totalEvidenceBudgetTokens / Math.max(1, executionCount));
        ContextTokenEstimator.Size before = contextTokenEstimator.estimate(execution.output())
            .plus(contextTokenEstimator.estimate(execution.metadata()));
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, Object> outputFacts = structuredOutputFacts(execution.output());
        if (!outputFacts.isEmpty()) {
            content.put("outputFacts", outputFacts);
        }
        String authoritativeEvidence = execution.success()
            ? toolObservationBuilder.buildAuthoritativeExecutionEvidence(
                execution.toolName(),
                execution.output()
            )
            : null;
        boolean preserveStructuredBatchEvidence = false;
        String strategy;
        String lossLevel;
        if (authoritativeEvidence != null
            && !authoritativeEvidence.isBlank()
            && contextTokenEstimator.estimate(authoritativeEvidence).tokens() <= perEvidenceBudget) {
            Map<String, Object> structuredAuthoritativeEvidence = asMap(authoritativeEvidence);
            preserveStructuredBatchEvidence = "batch_execution_evidence.v1".equals(
                stringValue(structuredAuthoritativeEvidence.get("schemaVersion")));
            content.put("semanticEvidence", structuredAuthoritativeEvidence.isEmpty()
                ? authoritativeEvidence
                : structuredAuthoritativeEvidence);
            content.put("metadata", contextEvidenceAggregator.aggregate(execution.metadata()));
            strategy = "TOOL_SEMANTIC_PROJECTION";
            lossLevel = "LOW";
        } else {
            Object structuredEvidence = contextEvidenceAggregator.aggregate(execution.output());
            content.put("structuredEvidence", structuredEvidence == null
                ? Map.of("valuePresent", false)
                : structuredEvidence);
            content.put("metadata", contextEvidenceAggregator.aggregate(execution.metadata()));
            strategy = "STRUCTURED_AGGREGATION";
            lossLevel = "MEDIUM";
        }
        int boundedChars = Math.max(2_000, perEvidenceBudget * 2);
        int contentBudget = Math.max(1_000, perEvidenceBudget - 1_000);
        Object boundedContent = preserveStructuredBatchEvidence
            && contextTokenEstimator.estimate(content).tokens() <= contentBudget
            ? content
            : ToolLogSummarizer.summarize(content, boundedChars);
        if (boundedContent instanceof Map<?, ?> boundedMap) {
            content = asStringObjectMap(boundedMap);
        } else {
            content = new LinkedHashMap<>(Map.of(
                "summary", boundedContent == null ? "" : boundedContent));
        }
        strategy = strategy + "_BOUNDED";
        ContextTokenEstimator.Size after = contextTokenEstimator.estimate(content);
        String evidenceId = "step:" + execution.stepId() + ":tool:"
            + firstNonBlank(execution.toolName(), execution.actionType());
        return new ContextCompressionEnvelope(
            evidenceId,
            Map.copyOf(content),
            strategy,
            lossLevel,
            before,
            after,
            perEvidenceBudget
        ).asMap();
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
        String runId = request.runId();
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
                Map.of(
                    "toolResultReviewRaw", preview(raw),
                    "toolResultReviewUnavailable", true
                )
            );
        }
        if (payload.containsKey("error")
            && firstObject(payload, "satisfied", "accepted", "sufficient") == null) {
            return InterpretationPlanRuntime.StepReview.rejected(
                "Tool result reviewer was unavailable: " + preview(stringify(payload.get("error"))),
                Map.of(
                    "toolResultReviewRaw", preview(raw),
                    "toolResultReviewUnavailable", true
                )
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
        List<String> selectedAssetIds = stringList(firstObject(payload, "selected_asset_ids", "selectedAssetIds"));
        if (!selectedAssetIds.isEmpty()) metadata.put("selectedAssetIds", selectedAssetIds);
        List<String> rejectedAssetIds = stringList(firstObject(payload, "rejected_asset_ids", "rejectedAssetIds"));
        if (!rejectedAssetIds.isEmpty()) metadata.put("rejectedAssetIds", rejectedAssetIds);
        Object assetEvaluations = firstObject(payload, "asset_evaluations", "assetEvaluations");
        if (assetEvaluations instanceof Iterable<?>) metadata.put("assetEvaluations", assetEvaluations);
        List<String> selectedTemplateIds = stringList(firstObject(payload, "selected_template_ids", "selectedTemplateIds"));
        if (!selectedTemplateIds.isEmpty()) {
            metadata.put("selectedTemplateIds", selectedTemplateIds);
        }
        List<String> rejectedTemplateIds = stringList(firstObject(payload, "rejected_template_ids", "rejectedTemplateIds"));
        if (!rejectedTemplateIds.isEmpty()) {
            metadata.put("rejectedTemplateIds", rejectedTemplateIds);
        }
        Object templateEvaluations = firstObject(payload, "template_evaluations", "templateEvaluations");
        if (templateEvaluations instanceof Iterable<?>) {
            metadata.put("templateEvaluations", templateEvaluations);
        }
        List<String> missingParameters = stringList(firstObject(payload,
            "missing_parameters", "missingParameters", "required_parameters_missing", "requiredParametersMissing"));
        if (!missingParameters.isEmpty()) {
            metadata.put("missingParameters", missingParameters);
        }
        Map<String, Object> retryInputChanges = asMap(firstObject(payload,
            "retry_input_changes", "retryInputChanges", "parameter_changes", "parameterChanges"));
        if (!retryInputChanges.isEmpty()) {
            metadata.put("retryInputChanges", retryInputChanges);
        }
        Object reselectTemplate = firstObject(payload,
            "reselect_template", "reselectTemplate", "template_reselection_required", "templateReselectionRequired");
        if (reselectTemplate != null) {
            metadata.put("templateReselectionRequired", booleanValue(reselectTemplate));
        }
        Object executionSatisfied = firstObject(payload,
            "template_execution_satisfied", "templateExecutionSatisfied", "execution_satisfied", "executionSatisfied");
        if (executionSatisfied != null) {
            metadata.put("templateExecutionSatisfied", booleanValue(executionSatisfied));
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
        prompt.append("System policy inheritance: the validated step already carries the user intent, scope, "
            + "constraints, and approved tool. Review only this execution and do not expand that scope.\n\n");
        prompt.append("You are the runtime reviewer for one completed MCP tool call.\n");
        prompt.append("Return strict JSON only with this shape:\n");
        prompt.append("{\"satisfied\":true|false,\"iteration_sufficient\":true|false,\"reason\":\"short reason\",\"review_answer\":\"optional audit note, not user-facing final answer\",\"evidence_used\":[{\"basis\":\"returned fact\"}],\"missing_evidence\":[\"material gap\"],\"conflicts\":[\"conflict\"],\"hypotheses\":[{\"hypothesis_id\":\"H1\",\"parent_hypothesis_id\":null,\"statement\":\"testable explanation\",\"support_evidence_ids\":[],\"contradict_evidence_ids\":[],\"confidence\":0.0,\"status\":\"SUPPORTED|CONTRADICTED|UNRESOLVED\"}],\"next_actions\":[{\"tool\":\"available_tool_name\",\"intent\":\"evidence gap to close or hypothesis to test\",\"input_changes\":{\"parameter\":\"revised value\"},\"reason\":\"why this action is needed\",\"based_on\":[\"evidenceId\",\"hypothesisId\"],\"scope_basis\":{\"source\":\"user_query|tool_result\",\"reference\":\"exact user quote or returned JSON path\"},\"capability_basis\":{\"source\":\"tool_result|tool_metadata\",\"reference\":\"returned capability JSON path or declared tool capability\"},\"expected_evidence_types\":[\"specific evidence type\"]}],\"selected_urls\":[\"https://...\"],\"useful_refs\":[\"doc://...#chunk=0\"],\"rejected_refs\":[\"doc://...#chunk=1\"],\"selected_asset_ids\":[\"asset-id\"],\"rejected_asset_ids\":[\"asset-id\"],\"asset_evaluations\":[{\"asset_id\":\"asset-id\",\"relevance\":0.0,\"decision\":\"accept|reject\",\"reasons\":[\"evidence-based reason\"]}],\"selected_template_ids\":[\"template-id\"],\"rejected_template_ids\":[\"template-id\"],\"template_evaluations\":[{\"template_id\":\"template-id\",\"relevance\":0.0,\"evidence_fit\":0.0,\"parameter_readiness\":0.0,\"total_score\":0.0,\"decision\":\"accept|reject\",\"reasons\":[\"evidence-based reason\"],\"missing_parameters\":[]}],\"template_execution_satisfied\":true|false,\"missing_parameters\":[\"parameter\"],\"retry_input_changes\":{\"parameters\":{\"parameter\":\"value proven by user/tool evidence\"}},\"reselect_template\":true|false,\"refined_intent\":\"optional refined retrieval intent\",\"relevance\":0.0,\"answerability\":0.0,\"supportsQuestionAspect\":[\"process\"],\"missingAspects\":[\"constraints\"],\"usefulness\":\"HIGH|MEDIUM|LOW\",\"shouldExpandQuery\":true|false,\"confidence\":0.0}\n");
        prompt.append("Rules:\n");
        prompt.append(AgentRuntimeFactGroundingContract.promptSection());
        prompt.append("- Decide whether this tool output is sufficient for the current plan step and user request.\n");
        prompt.append("- iteration_sufficient evaluates the cumulative user request, not merely whether this one tool call technically succeeded. Set it false when material evidence is still missing and provide evidence_used, missing_evidence, conflicts, and tool-agnostic next_actions.\n");
        prompt.append("- satisfied and iteration_sufficient describe semantic usefulness and evidence sufficiency; they do not control or rewrite the tool execution status.\n");
        prompt.append("- outputTruncated=true means result completeness is partial. If the ToolOutput itself succeeded, record the returned evidence and its limits; never describe the tool call or Runtime step as failed solely because content is truncated.\n");
        prompt.append("- next_actions may revise the current tool input, call another available tool, validate a conflict, or retrieve a missing fact. Do not assume any particular tool type.\n");
        prompt.append("- A broad category phrase in the user query is not permission to invent a conventional checklist. Every missing_evidence, missingAspects, refined_intent expansion, hypothesis, and next_action must be traceable either to exact current-turn user wording or to a criterion explicitly present in returned evidence.\n");
        prompt.append("- Every next_action must include scope_basis, capability_basis, and expected_evidence_types. scope_basis must quote exact current-turn user text or name a returned JSON path that establishes the gap. capability_basis must identify returned or declared tool capability that can produce the expected evidence. If either basis is unavailable, omit the action and set shouldExpandQuery=false.\n");
        prompt.append("- A revised query cannot expand a tool's declared capability. Never propose a next_action using a tool whose returned capability/claim-coverage contract explicitly marks the requested evidence as unsupported or not provided. Use another available capability that explicitly covers the gap; if none exists, set shouldExpandQuery=false and preserve the gap for a bounded final answer.\n");
        prompt.append("- hypotheses must be testable explanations, not facts. Mark each SUPPORTED, CONTRADICTED, or UNRESOLVED and relate it to returned evidence. Runtime will bind the current evidenceId when the model cannot know it yet.\n");
        prompt.append("- Preserve a hypothesis_id when the same hypothesis is refined later; create a new id only for a materially different explanation.\n");
        prompt.append("- Use parent_hypothesis_id to decompose a broad hypothesis into independently testable child hypotheses. Do not create cycles or make a hypothesis its own parent.\n");
        prompt.append("- If satisfied=false, explain missing aspects, but never discard succeeded SQL/database rows merely because they are partial or imperfect.\n");
        prompt.append("- For SQL/database outputs, any returned rows, columns, metrics, or result sets are usable partial evidence. Mark them satisfied=true when they can support any part of the answer, and list gaps in missingAspects.\n");
        prompt.append("- For any exact lookup that returns a structurally successful empty result, keep execution success distinct from answerability. If discovery is still required and budget remains, set shouldExpandQuery=true and propose one materially revised available-tool call that relaxes the blocking exact filter and uses bounded alternative tokens derived from the user request or returned diagnostics. Candidate variants are retrieval inputs, never facts that an object exists.\n");
        prompt.append("- Never propose a downstream binding such as tables[0], results[0], or another indexed element when the returned collection is empty. Route to evidence recovery or a bounded partial answer instead.\n");
        prompt.append("- For web discovery tools (web_search, web_page_analyze, site_intelligence_resolver, *_site_search), judge candidate URLs/snippets only. Do not require full article content from these tools.\n");
        prompt.append("- If a web discovery tool returns useful URLs for follow-up crawling or page analysis, set satisfied=true and put those URLs in selected_urls.\n");
        prompt.append("- For crawl/content tools, judge whether the fetched full content is relevant and usable for analysis.\n");
        prompt.append("- For document_search, judge whether the result contains relevant document evidence that can support later synthesis. Do not require one chunk to contain the complete final answer or every requested example.\n");
        prompt.append("- Accept document_search when multiple chunks collectively mention relevant entities, APIs, tables, citations, or snippets, even if the final answer must combine them and state missing pieces.\n");
        prompt.append("- Reject document_search only when it failed, returned no useful results, violated an explicit source constraint, or is unrelated to the request.\n");
        prompt.append("- For document_search, evaluate each returned document/chunk against the current user request. Put useful doc:// refs in useful_refs and unrelated or misleading refs in rejected_refs. Do not infer usefulness from retrieval rank alone.\n");
        prompt.append("- Treat retrieval score as a weak prior only. Your semantic evidence evaluation must state relevance, answerability, supported aspects, missing aspects, usefulness, and whether another query expansion is needed.\n");
        prompt.append("- For template discovery and API/HTTP requirement analysis, compare title, description, capabilitySpec, outputSchema, dependencySpec and required parameters with the current requirement. Return only ids present in the tool output under selected_template_ids/rejected_template_ids. If candidates do not cover the requirement, set satisfied=false and provide refined_intent.\n");
        prompt.append("- When the plan has diagnostic_profile checks, selected templates must cover those checks by their full declared capability and dimension meaning. A single generic shared token is insufficient. Prefer the candidate that matches the check-specific template metadata; reject unrelated substitutes and request refined retrieval when the intended capability is absent.\n");
        prompt.append("- For asset discovery with multiple candidates, compare only returned routing metadata with the current target. Return selected_asset_ids/rejected_asset_ids and one asset_evaluations entry per candidate. Asset discovery proves routing eligibility, never target health or business state.\n");
        prompt.append("- Template retrieval scores and ordering are weak recall priors, never acceptance decisions. Semantically review every returned template candidate.\n");
        prompt.append("- When governed template discovery returns multiple admitted templates, every template remaining in that discovery result is execution-required. A scalar templates[0] plan binding does not reduce this set: Runtime compiles all admitted templates into a failure-isolated batch and final synthesis must wait for a terminal result from every call. selected_template_ids may order or narrow candidates only during the discovery admission decision; it may never be used after admission to skip physical execution.\n");
        prompt.append("- Put unrelated or materially weaker candidates in rejected_template_ids. Do not select a template merely because Lucene ranked it first or its score ties another candidate.\n");
        prompt.append("- For each returned template candidate, emit template_evaluations with evidence-based relevance, evidence_fit, parameter_readiness, total_score, decision, reasons, and missing_parameters. Scores are 0..1 and must be justified by returned metadata and the current user request.\n");
        prompt.append("- For a template execution tool, set template_execution_satisfied explicitly. If false, list missing_parameters and provide retry_input_changes only for values proven by the user query or completed tool evidence; otherwise leave retry_input_changes empty and set reselect_template=true.\n");
        prompt.append("- A failed template execution gets at most one repaired plan execution. The repair must materially add/bind parameters or reselect a different authorized candidate; never request an unchanged retry.\n");
        prompt.append("- If the user required an official source, reject results that do not satisfy that source constraint.\n");
        prompt.append("- Do not answer the user here; only review the tool result.\n\n");
        prompt.append("- Never write final_answer/finalAnswer in this reviewer JSON. If you need to propose wording for audit, write review_answer; it will not become the user-facing answer.\n");
        prompt.append("- Runtime deterministic fact check is non-overridable: do not contradict returned counts or extracted metadata facts. You may still reject for semantic mismatch, wrong template, wrong target, or missing follow-up evidence.\n");
        prompt.append("- The Current-turn user query below is the only user-authored source for an explicitly requested target. Plan intent and Current step are model-generated and must never be described as 'the user specified' unless the exact target text also appears in the current-turn user query.\n");
        prompt.append("- Prefer the tool output's routing/default-asset facts when stating which asset was actually queried. Do not confuse a requested target, datasource asset, database/schema, and table name.\n");
        prompt.append("- If assetDiscoveryReturnedCount > 0, do not claim the asset query returned zero/no assets.\n");
        prompt.append("- If templateDiscoveryReturnedCount > 0, do not claim the template query returned zero/no templates.\n");
        prompt.append("- If sqlMetadataColumnCount > 0, do not claim the SQL metadata step returned no columns/metadata.\n");
        prompt.append("- Runtime must pass the complete tool-returned result. Any limit, pagination, or truncation marker must originate from the tool contract, never from Runtime prompt construction.\n");
        prompt.append("- For enterprise metadata matching, use the formatted authoritative evidence projection when present. Its coverage object distinguishes processed fields from fields with candidates; never treat success=true or explicitTruncation=false as proof that every input field matched a standard.\n");
        prompt.append("- For enterprise metadata discovery, treat evidenceCoverage as a description of returned standard-reference data, not as an answerability or conformance verdict. Evaluate semantic usefulness from the returned records and current user request; do not manufacture unsupported/missing claim lists from the coverage descriptor.\n");
        prompt.append("- Enterprise metadata records describe fields, terms, roots, and dictionaries. Do not reinterpret a generic request for enterprise standards as a request for unrelated design dimensions unless those dimensions occur verbatim in the user query or as explicit criteria in returned records.\n");
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
        String authoritativeEvidence = toolObservationBuilder.buildAuthoritativeExecutionEvidence(
            request.execution().toolName(), request.execution().output());
        if (authoritativeEvidence != null && !authoritativeEvidence.isBlank()) {
            prompt.append("Authoritative tool result evidence (formatted under the tool's reasoning-selection contract; complete raw results remain in the tool trace):\n")
                .append(authoritativeEvidence)
                .append("\nPrompt preview truncated: false");
        } else {
            String serializedOutput = stringify(redactExecutionStatementText(request.execution().output()));
            prompt.append("Complete tool result:\n")
                .append(serializedOutput)
                .append("\nRuntime truncation applied: false");
        }
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
        Map<String, Object> enterpriseRoot = enterpriseMetadataResultRoot(root, 0);
        if (!enterpriseRoot.isEmpty()) {
            root = enterpriseRoot;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        putIfPresent(facts, "schemaVersion", root.get("schemaVersion"));
        putIfPresent(facts, "category", root.get("category"));
        putIfPresent(facts, "dataSchemaVersion", root.get("dataSchemaVersion"));
        putIfPresent(facts, "success", root.get("success"));
        putIfPresent(facts, "status", root.get("status"));
        putIfPresent(facts, "errorMessage", root.get("errorMessage"));

        if (isEnterpriseMetadataResult(root)) {
            Map<String, Object> sourceSchema = asMap(root.get("sourceSchema"));
            Map<String, Object> coverage = asMap(root.get("coverage"));
            int sourceFieldCount = intValue(sourceSchema.get("fieldCount"), collectionSize(sourceSchema.get("fields")));
            int returnedFieldMatchCount = collectionSize(root.get("fieldMatches"));
            facts.put("sourceFieldCount", sourceFieldCount);
            facts.put("returnedFieldMatchCount", returnedFieldMatchCount);
            facts.put("coverage", compactMap(
                coverage,
                "inputFieldCount",
                "processedFieldCount",
                "allFieldsProcessed",
                "requiredMetadataTypes",
                "perFieldTypeRetrieval"
            ));
            facts.put("explicitTruncation", hasExplicitTruncationMarker(output, 0, new Counter()));
            facts.put("completenessSemantics",
                "allFieldsProcessed proves processing coverage only; per-field candidates prove metadata matches");
            return facts;
        }

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

    private boolean isEnterpriseMetadataResult(Map<String, Object> root) {
        String schemaVersion = stringValue(root == null ? null : root.get("schemaVersion"));
        return "enterprise_metadata_field_discovery.v1".equals(schemaVersion);
    }

    private Map<String, Object> enterpriseMetadataResultRoot(Object value, int depth) {
        if (depth > 5) {
            return Map.of();
        }
        Map<String, Object> candidate = asMap(value);
        if (candidate.isEmpty()) {
            return Map.of();
        }
        if (isEnterpriseMetadataResult(candidate)) {
            return candidate;
        }
        for (String key : List.of("data", "result", "payload", "structuredContent", "structured_content")) {
            Map<String, Object> nested = enterpriseMetadataResultRoot(candidate.get(key), depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return Map.of();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
            boolean partialEvidenceAccepted = Boolean.TRUE.equals(
                stepMetadata.get("toolResultReviewPartialAccepted"));
            if (!partialEvidenceAccepted
                && Boolean.FALSE.equals(stepMetadata.get("toolResultReviewSatisfied"))) {
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
        int runtimeMaximum = MAX_INTERPRETATION_PLAN_ATTEMPTS - 1;
        if (plan == null || plan.executionPolicy() == null
            || plan.executionPolicy().maxRewriteTimes() == null) {
            return runtimeMaximum;
        }
        return Math.max(0, Math.min(runtimeMaximum, plan.executionPolicy().maxRewriteTimes()));
    }

    private Object authoritativeWorkflowDagForContinuation(Object rawDag,
                                                            InterpretationPlan rewrittenPlan,
                                                            Set<String> completedTools) {
        if (!(rawDag instanceof Collection<?> nodes) || nodes.isEmpty()
            || completedTools == null || completedTools.isEmpty()
            || rewrittenPlan == null || rewrittenPlan.steps() == null) {
            return rawDag;
        }
        Set<String> plannedTools = rewrittenPlan.steps().stream()
            .filter(Objects::nonNull)
            .map(InterpretationPlan.Step::toolName)
            .filter(Objects::nonNull)
            .map(this::toolSemanticKey)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Long> plannedToolCounts = rewrittenPlan.steps().stream()
            .filter(Objects::nonNull)
            .map(InterpretationPlan.Step::toolName)
            .filter(Objects::nonNull)
            .map(this::toolSemanticKey)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.groupingBy(
                value -> value,
                LinkedHashMap::new,
                java.util.stream.Collectors.counting()
            ));
        Set<String> completedWorkflowTools = completedTools.stream()
            .filter(Objects::nonNull)
            .map(this::toolSemanticKey)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // A repeated tool represents a completed historical call plus a new refinement call.
        // The original workflow's exactly-once contract has already been satisfied and must not
        // reject the continuation merely because it reuses the same capability with new input.
        Set<String> completedToolsOutsideContinuationContract = completedWorkflowTools.stream()
            .filter(value -> !plannedTools.contains(value)
                || plannedToolCounts.getOrDefault(value, 0L) > 1L)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (completedToolsOutsideContinuationContract.isEmpty()) {
            return rawDag;
        }
        List<Map<String, Object>> continuationDag = new ArrayList<>();
        for (Object rawNode : nodes) {
            if (!(rawNode instanceof Map<?, ?> node)) {
                continue;
            }
            String toolName = firstNonBlank(
                stringValue(node.get("tool")),
                stringValue(node.get("toolName"))
            );
            if (completedToolsOutsideContinuationContract.contains(toolSemanticKey(toolName))) {
                continue;
            }
            Map<String, Object> continuationNode = new LinkedHashMap<>();
            node.forEach((key, value) -> {
                if (key != null) {
                    continuationNode.put(String.valueOf(key), value);
                }
            });
            Object dependencies = node.get("dependsOnTools");
            if (dependencies instanceof Collection<?> values) {
                continuationNode.put("dependsOnTools", values.stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !completedToolsOutsideContinuationContract.contains(
                        toolSemanticKey(String.valueOf(value))))
                    .toList());
            }
            continuationDag.add(continuationNode);
        }
        return continuationDag;
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
        checkCancelledUnlessBatchEvidence(cancellationCheck, result, metadata);
        List<Map<String, Object>> toolEvidence = interpretationToolEvidence(plan, result, iteration);
        DiagnosticRun diagnosticRun = diagnosticRun(result);
        Set<String> previouslyCompletedDiagnosticChecks = completedDiagnosticChecks(previousEvidence);
        boolean diagnosticCoverageComplete = diagnosticCoverageComplete(
            diagnosticRun,
            previouslyCompletedDiagnosticChecks
        );
        boolean diagnosticRetriesExhausted = diagnosticRun != null
            && diagnosticRun.confidenceEngine() != null
            && diagnosticRun.confidenceEngine().remainingRetries() == 0;
        boolean fallbackSufficient = result != null && result.success()
            && (diagnosticCoverageComplete || diagnosticRetriesExhausted);
        Map<String, Object> analysis = new LinkedHashMap<>();
        List<Object> evidenceUsed = new ArrayList<>();
        List<Object> missingEvidence = new ArrayList<>();
        List<Object> conflicts = new ArrayList<>();
        List<Object> nextActions = new ArrayList<>();
        List<String> conclusions = new ArrayList<>();
        List<Map<String, Object>> currentHypotheses = new ArrayList<>();
        boolean explicitIterationDecision = false;
        boolean iterationSufficient = fallbackSufficient;
        if (diagnosticRun != null) {
            for (DiagnosticRun.CheckResult check : diagnosticRun.checks()) {
                if (check == null || !check.required()
                    || "completed".equals(check.status())
                    || previouslyCompletedDiagnosticChecks.contains(normalizedDiagnosticCheckId(check.checkId()))) {
                    continue;
                }
                Map<String, Object> gap = metadataOf(
                    "type", "diagnostic_check",
                    "checkId", check.checkId(),
                    "capability", check.capability(),
                    "dimension", check.dimension(),
                    "status", check.status(),
                    "reason", firstNonBlank(check.reason(), "missing_diagnostic_evidence")
                );
                missingEvidence.add(gap);
                nextActions.add(metadataOf(
                    "action", "complete_diagnostic_check",
                    "checkId", check.checkId(),
                    "capability", check.capability(),
                    "dimension", check.dimension(),
                    "reason", firstNonBlank(check.reason(), "missing_diagnostic_evidence")
                ));
            }
            conclusions.add("Diagnostic coverage completed "
                + diagnosticRun.coverage().completed() + "/" + diagnosticRun.coverage().required()
                + " required checks in this attempt.");
            if (diagnosticRun.confidenceEngine() != null) {
                conclusions.add("Weighted evidence coverage is "
                    + diagnosticRun.confidenceEngine().weightedCoverage()
                    + " with evidence level "
                    + diagnosticRun.confidenceEngine().evidenceLevel()
                    + "; completion status="
                    + diagnosticRun.confidenceEngine().completionStatus() + ".");
            }
        }
        for (Map<String, Object> item : toolEvidence) {
            if (Boolean.TRUE.equals(item.get("success"))) {
                evidenceUsed.add(metadataOf(
                    "evidence_id", item.get("evidenceId"),
                    "basis", firstNonBlank(stringValue(item.get("reviewReason")), "successful MCP result")
                ));
            }
            addEvidenceAnalysisItems(missingEvidence, item.get("missingEvidence"));
            addEvidenceAnalysisItems(conflicts, item.get("conflicts"));
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
        nextActions.addAll(pendingEvidenceNextActions(toolEvidence));
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
        if (diagnosticRun != null) {
            snapshot.put("diagnosticRun", diagnosticRun);
            snapshot.put("diagnosticCoverageComplete", diagnosticCoverageComplete);
            snapshot.put("previouslyCompletedDiagnosticChecks", previouslyCompletedDiagnosticChecks);
        }
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

    private DiagnosticRun diagnosticRun(InterpretationPlanRuntime.ExecutionResult result) {
        Object value = result == null || result.metadata() == null
            ? null
            : result.metadata().get("diagnosticRun");
        return value instanceof DiagnosticRun run ? run : null;
    }

    private Set<String> completedDiagnosticChecks(List<Map<String, Object>> previousEvidence) {
        Set<String> completed = new LinkedHashSet<>();
        for (Map<String, Object> snapshot : previousEvidence == null ? List.<Map<String, Object>>of() : previousEvidence) {
            Object value = snapshot == null ? null : snapshot.get("diagnosticRun");
            if (!(value instanceof DiagnosticRun run) || run.checks() == null) {
                continue;
            }
            run.checks().stream()
                .filter(check -> check != null && "completed".equals(check.status()))
                .map(DiagnosticRun.CheckResult::checkId)
                .map(this::normalizedDiagnosticCheckId)
                .filter(checkId -> !checkId.isBlank())
                .forEach(completed::add);
        }
        return completed;
    }

    private boolean diagnosticCoverageComplete(DiagnosticRun run, Set<String> previouslyCompleted) {
        if (run == null || run.checks() == null) {
            return true;
        }
        Set<String> prior = previouslyCompleted == null ? Set.of() : previouslyCompleted;
        return run.checks().stream()
            .filter(check -> check != null && check.required())
            .allMatch(check -> "completed".equals(check.status())
                || prior.contains(normalizedDiagnosticCheckId(check.checkId())));
    }

    private String normalizedDiagnosticCheckId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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
            Map<String, Object> evidenceEvaluation = step.metadata() == null
                ? Map.of()
                : asMap(step.metadata().get("evidenceEvaluation"));
            item.put("shouldExpandQuery", evidenceEvaluation.get("shouldExpandQuery"));
            item.put("hypotheses", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("hypotheses", List.of()));
            item.put("templateExecutionReview", step.metadata() == null ? Map.of()
                : step.metadata().getOrDefault("templateExecutionReview", Map.of()));
            item.put("missingParameters", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("templateExecutionMissingParameters", List.of()));
            item.put("retryInputChanges", step.metadata() == null ? Map.of()
                : step.metadata().getOrDefault("templateExecutionRetryInputChanges", Map.of()));
            item.put("templateReselectionRequired", step.metadata() != null
                && Boolean.TRUE.equals(step.metadata().get("templateReselectionRequired")));
            if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
                item.put("error", step.errorMessage());
            }
            item.put("output", step.output());
            item.put("outputFacts", structuredOutputFacts(step.output()));
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

    private EvidenceAugmentationPolicy.Outcome decideEvidenceAugmentation(
        Map<String, Object> snapshot,
        InterpretationPlanRuntime.ExecutionResult result,
        boolean explorationAvailable,
        boolean authorizationRequired,
        Map<String, Object> metadata
    ) {
        boolean sufficient = evidenceSufficient(snapshot);
        boolean materialGap = !sufficient && (
            result == null
                || !result.success()
                || collectionSize(snapshot == null ? null : snapshot.get("remainingMissing")) > 0
                || collectionSize(snapshot == null ? null : snapshot.get("conflicts")) > 0
        );
        return evidenceAugmentationPolicy.decide(new EvidenceAugmentationPolicy.Context(
            usableEvidenceAvailable(snapshot),
            sufficient,
            materialGap,
            explorationAvailable,
            authorizationRequired,
            taskEvidenceRequirement(metadata)
        ));
    }

    boolean evidenceExplorationAvailable(Map<String, Object> snapshot,
                                         InterpretationPlanRuntime.ExecutionResult result,
                                         List<String> availableTools,
                                         boolean budgetAvailable) {
        if (!budgetAvailable || availableTools == null || availableTools.isEmpty()) {
            return false;
        }
        if (result == null || !result.success()) {
            return true;
        }
        if (snapshot == null) {
            return false;
        }
        return !evidenceRefinementRequiredTools(List.of(snapshot), availableTools).isEmpty();
    }

    List<Object> pendingEvidenceNextActions(List<Map<String, Object>> toolEvidence) {
        if (toolEvidence == null || toolEvidence.isEmpty()) {
            return List.of();
        }
        List<Object> pending = new ArrayList<>();
        for (int index = 0; index < toolEvidence.size(); index++) {
            Map<String, Object> source = toolEvidence.get(index);
            if (source == null) {
                continue;
            }
            Object rawActions = source.get("nextActions");
            if (!(rawActions instanceof Iterable<?> actions)) {
                continue;
            }
            for (Object rawAction : actions) {
                if (!(rawAction instanceof Map<?, ?> rawActionMap)) {
                    addEvidenceAnalysisItems(pending, rawAction);
                    continue;
                }
                Map<String, Object> action = asStringObjectMap(rawActionMap);
                String requestedTool = stringValue(firstObject(action,
                    "tool", "toolName", "tool_name"));
                if (requestedTool != null
                    && Boolean.FALSE.equals(source.get("shouldExpandQuery"))) {
                    continue;
                }
                boolean alreadyExecutedSuccessfully = false;
                if (requestedTool != null) {
                    for (Map<String, Object> executed : toolEvidence) {
                        if (executed != null
                            && Boolean.TRUE.equals(executed.get("success"))
                            && toolNames.sameToolName(requestedTool, stringValue(executed.get("tool")))) {
                            alreadyExecutedSuccessfully = true;
                            break;
                        }
                    }
                }
                boolean evidenceBackedRevision = Boolean.TRUE.equals(source.get("shouldExpandQuery"))
                    && hasMaterialEvidenceActionRevision(action);
                if (!alreadyExecutedSuccessfully || evidenceBackedRevision) {
                    pending.add(action);
                }
            }
        }
        return List.copyOf(pending);
    }

    private boolean hasMaterialEvidenceActionRevision(Map<String, Object> action) {
        if (action == null || action.isEmpty()) {
            return false;
        }
        Object changes = firstObject(action,
            "input_changes", "inputChanges", "retry_input_changes", "retryInputChanges");
        if (changes instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (changes instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return changes != null && !String.valueOf(changes).isBlank();
    }

    private TaskContract.EvidenceRequirement taskEvidenceRequirement(Map<String, Object> metadata) {
        Object contract = metadata == null ? null : metadata.get("taskContract");
        if (contract instanceof TaskContract taskContract) {
            return taskContract.evidenceRequirement();
        }
        String configured = stringValue(metadata == null ? null : metadata.get("evidenceRequirement"));
        if (configured != null) {
            try {
                return TaskContract.EvidenceRequirement.valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Use the safe default below for older runtime metadata.
            }
        }
        return TaskContract.EvidenceRequirement.OPTIONAL;
    }

    private boolean usableEvidenceAvailable(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("toolEvidence") instanceof Iterable<?> evidenceItems)) {
            return false;
        }
        for (Object rawItem : evidenceItems) {
            if (!(rawItem instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> item = asStringObjectMap(rawMap);
            if (meaningfulEvidenceValue(item.get("outputFacts"))
                || meaningfulEvidenceValue(item.get("output"))) {
                return true;
            }
        }
        return false;
    }

    private boolean meaningfulEvidenceValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            return !normalized.isEmpty()
                && !"null".equalsIgnoreCase(normalized)
                && !"[]".equals(normalized)
                && !"{}".equals(normalized);
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty() && map.values().stream().anyMatch(this::meaningfulEvidenceValue);
        }
        if (value instanceof Iterable<?> values) {
            return values.iterator().hasNext();
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) > 0;
        }
        return true;
    }

    private void recordEvidenceAugmentationDecision(
        EvidenceAugmentationPolicy.Outcome outcome,
        int iteration,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        if (outcome == null || metadata == null) {
            return;
        }
        Map<String, Object> decision = metadataOf(
            "contractVersion", outcome.contractVersion(),
            "iteration", iteration,
            "decision", outcome.decision().name(),
            "answerAllowed", outcome.answerAllowed(),
            "continueLoop", outcome.continueLoop(),
            "reason", outcome.reason()
        );
        addCandidateList(metadataList(metadata, "evidenceAugmentationHistory"), List.of(decision));
        metadata.put("evidenceAugmentationDecision", outcome.decision().name());
        metadata.put("evidenceAugmentationAnswerAllowed", outcome.answerAllowed());
        metadata.put("evidenceAugmentationContinueLoop", outcome.continueLoop());
        metadata.put("evidenceAugmentationContractVersion", outcome.contractVersion());
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            "Evidence augmentation decision for iteration " + iteration + ": "
                + outcome.decision().name() + ".",
            "evidence_augmentation_decision",
            metadataOf(
                "type", "evidence",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "loop_decision",
                "decision", decision
            )
        );
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

    private void recordDagRepairEvent(Map<String, Object> runtimeAttributes,
                                      Map<String, Object> metadata,
                                      String eventState,
                                      int rewriteCount,
                                      String reason,
                                      InterpretationPlan.Step failedStep,
                                      List<Map<String, Object>> changes,
                                      InterpretationPlanValidator.ValidationResult validation) {
        String normalizedState = firstNonBlank(eventState, "UNKNOWN").toUpperCase(Locale.ROOT);
        List<Map<String, Object>> safeChanges = changes == null ? List.of() : List.copyOf(changes);
        List<InterpretationPlanValidator.ValidationIssue> validationIssues = validation == null
            ? List.of()
            : validation.issues();
        Map<String, Object> repairEvent = metadataOf(
            "contractVersion", dagGovernanceContractVersion(runtimeAttributes),
            "eventKind", "DAG_REPAIR",
            "eventState", normalizedState,
            "repairAttempt", rewriteCount,
            "fromIteration", rewriteCount,
            "toIteration", rewriteCount + 1,
            "reason", firstNonBlank(reason, "Runtime detected an evidence or execution gap."),
            "failedStepId", failedStep == null ? null : failedStep.id(),
            "failedToolName", failedStep == null ? null : failedStep.toolName(),
            "changeCount", safeChanges.size(),
            "changes", safeChanges,
            "validationIssues", validationIssues,
            "createdAt", System.currentTimeMillis()
        );
        if (metadata != null) {
            metadataList(metadata, "dagRepairEvents").add(repairEvent);
        }
        String content = switch (normalizedState) {
            case "STARTED" -> "Runtime started DAG repair attempt " + rewriteCount
                + " after detecting a recoverable plan or execution failure.";
            case "APPLIED" -> "Runtime applied DAG repair attempt " + rewriteCount
                + " with " + safeChanges.size() + " audited change(s); validation passed.";
            case "REJECTED" -> "DAG repair attempt " + rewriteCount
                + " did not pass runtime validation; the next bounded recovery action will be evaluated.";
            default -> "Runtime recorded DAG repair attempt " + rewriteCount + ".";
        };
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            content,
            "interpretation_plan_repair",
            metadataOf(
                "type", "repair",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "dag_repair",
                "eventKind", "DAG_REPAIR",
                "eventState", normalizedState,
                "repairAttempt", rewriteCount,
                "repairEvent", repairEvent
            )
        );
    }

    private void recordPlannerDagRepairEvent(Map<String, Object> runtimeAttributes,
                                             Map<String, Object> metadata,
                                             Object rawRepairEvent) {
        Map<String, Object> repairEvent = asMap(rawRepairEvent);
        if (!"DAG_REPAIR".equalsIgnoreCase(stringValue(repairEvent.get("eventKind")))
            || repairEvent.isEmpty()) {
            return;
        }
        if (metadata != null) {
            metadataList(metadata, "dagRepairEvents").add(new LinkedHashMap<>(repairEvent));
        }
        String repairCode = firstNonBlank(
            stringValue(repairEvent.get("repairCode")), "AUTHORITATIVE_DAG_REPAIR");
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            "Runtime restored the planner DAG from the authoritative workflow contract ("
                + repairCode + ").",
            "interpretation_plan_repair",
            metadataOf(
                "type", "repair",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "dag_repair",
                "eventKind", "DAG_REPAIR",
                "eventState", firstNonBlank(
                    stringValue(repairEvent.get("eventState")), "APPLIED"),
                "repairEvent", repairEvent
            )
        );
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
        Object diagnosticRun = result.metadata() == null ? null : result.metadata().get("diagnosticRun");
        if (diagnosticRun != null) {
            metadata.put("diagnosticRun", diagnosticRun);
            metadata.put("interpretationPlan" + capitalize(stage) + "DiagnosticRun", diagnosticRun);
            Object diagnosticCoverage = result.metadata().get("diagnosticCoverage");
            Object diagnosticAssessment = result.metadata().get("diagnosticAssessment");
            if (diagnosticCoverage != null) {
                metadata.put("diagnosticCoverage", diagnosticCoverage);
            }
            if (diagnosticAssessment != null) {
                metadata.put("diagnosticAssessment", diagnosticAssessment);
            }
            observations.add("InterpretationPlan " + stage + " diagnostic coverage: "
                + shortObservationText(stringify(diagnosticRun), 2000));
        }
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
            Map<String, Object> dag = interpretationPlanDagConverter.convert(plan);
            attachDagGovernanceContract(dag, runtimeAttributes);
            InterpretationPlanRecord record = interpretationPlanStore.savePlan(
                firstNonBlank(tenantId, "default"),
                taskId,
                planId,
                plan,
                "GENERATED",
                dag
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
            attachDagGovernanceContract(dag, runtimeAttributes);
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

    private void attachDagGovernanceContract(Map<String, Object> dag,
                                             Map<String, Object> runtimeAttributes) {
        if (dag == null) {
            return;
        }
        Object contract = runtimeAttributes == null ? null
            : runtimeAttributes.get(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE);
        if (contract != null) {
            dag.put("governanceContract", contract);
        }
    }

    private String dagGovernanceContractVersion(Map<String, Object> runtimeAttributes) {
        Object raw = runtimeAttributes == null ? null
            : runtimeAttributes.get(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE);
        Object version = raw instanceof Map<?, ?> contract ? contract.get("contractVersion") : null;
        return version == null || String.valueOf(version).isBlank()
            ? DagGovernanceContractProvider.INITIAL_VERSION
            : String.valueOf(version);
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

    String templateSelectionFeedbackObservation(String stage,
                                                InterpretationPlanRuntime.StepExecution step) {
        if (step == null || step.metadata() == null || step.metadata().isEmpty()) {
            return null;
        }
        Map<String, Object> feedback = new LinkedHashMap<>();
        for (String key : List.of(
            "selectedTemplateIds",
            "rejectedTemplateIds",
            "templateEvaluations",
            "refinedIntent",
            "runtimeSelectedTemplateIds",
            "runtimeTemplateCandidateEvaluations",
            "runtimeTemplateSelectionReason",
            "templateExecutionReview",
            "templateReselectionRequired"
        )) {
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

    /**
     * Lets evidence, rather than a model's optimistic initial rewrite estimate,
     * decide whether another bounded refinement round is still useful. An
     * explicit zero remains the caller's stop decision; positive estimates may
     * expand only when the latest evidence names a tool that is actually in the
     * current availability snapshot.
     */
    int evidenceDrivenRewriteLimit(int configuredMaxRewriteTimes,
                                   EvidenceAugmentationPolicy.Outcome outcome,
                                   List<Map<String, Object>> evidenceHistory,
                                   List<String> availableTools) {
        int configured = Math.max(0,
            Math.min(MAX_INTERPRETATION_PLAN_ATTEMPTS - 1, configuredMaxRewriteTimes));
        if (configured == 0 || outcome == null || !outcome.continueLoop()) {
            return 0;
        }
        if (evidenceRefinementRequiredTools(evidenceHistory, availableTools).isEmpty()) {
            return configured;
        }
        return MAX_INTERPRETATION_PLAN_ATTEMPTS - 1;
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

    private InterpretationPlan.Step repairRootStep(InterpretationPlan plan,
                                                   InterpretationPlanRuntime.ExecutionResult result) {
        InterpretationPlan.Step failed = failedStep(plan, result);
        if (failed != null || plan == null || result == null || result.metadata() == null) {
            return failed;
        }
        Integer rootId = integerValue(result.metadata().get("failedStepId"));
        if (rootId == null) {
            List<Integer> remaining = integerList(result.metadata().get("remainingStepIds"));
            rootId = remaining.isEmpty() ? null : remaining.get(0);
        }
        if (rootId == null) {
            return null;
        }
        Integer selectedId = rootId;
        return plan.steps().stream()
            .filter(Objects::nonNull)
            .filter(step -> Objects.equals(step.id(), selectedId))
            .findFirst()
            .orElse(null);
    }

    private Map<Integer, InterpretationPlanRuntime.ReusableStep> reusablePlanSteps(
        Map<Integer, InterpretationPlanRuntime.ReusableStep> existing,
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        Map<Integer, InterpretationPlanRuntime.ReusableStep> reusable = new LinkedHashMap<>(
            existing == null ? Map.of() : existing);
        if (plan == null || result == null || result.steps() == null) {
            return reusable;
        }
        Map<Integer, InterpretationPlan.Step> definitions = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step != null && step.id() != null) {
                definitions.putIfAbsent(step.id(), step);
            }
        }
        for (InterpretationPlanRuntime.StepExecution execution : result.steps()) {
            InterpretationPlan.Step definition = execution == null ? null : definitions.get(execution.stepId());
            if (definition != null && execution.success()) {
                reusable.put(definition.id(), new InterpretationPlanRuntime.ReusableStep(definition, execution));
            }
        }
        return reusable;
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
        safeArguments = new LinkedHashMap<>(
            toolArguments.enforceObservedAssetContinuity(toolName, safeArguments, priorTraces));
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
        return new ToolCallExecution(trace, observation, output);
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
        if (output != null && output.getMetadata() != null
            && output.getMetadata().get("mcpEvidenceResult") != null) {
            metadata.put("mcpEvidenceResult", output.getMetadata().get("mcpEvidenceResult"));
            metadata.put("mcpEvidenceResultSchemaVersion",
                output.getMetadata().get("mcpEvidenceResultSchemaVersion"));
        }
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

    private void runMissingMandatoryWorkflowTools(ChatModel activeChatModel,
                                                  List<InteractionToolTrace> traces,
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
            List<InteractionToolTrace> predecessorTraces =
                mandatoryPredecessorTraces(mandatoryTools, fallbackTool, traces);
            Map<String, Object> predecessorReview =
                mandatoryWorkflowPredecessorReview(fallbackTool, predecessorTraces);
            if (!Boolean.TRUE.equals(predecessorReview.get("satisfied"))) {
                appendMandatoryWorkflowReview(metadata, predecessorReview);
                metadata.put("mandatoryWorkflowStoppedOnFailure",
                    predecessorReview.getOrDefault("predecessorToolName", fallbackTool));
                observations.add("Mandatory workflow fallback stopped by predecessor result review: "
                    + stringify(predecessorReview));
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
            fallbackArguments = toolArguments.applyDeterministicDependencyContracts(
                fallbackTool,
                fallbackArguments,
                predecessorTraces,
                query
            );
            List<String> missingRequiredInputs = missingRequiredToolInputs(fallbackTool, fallbackArguments);
            if (!missingRequiredInputs.isEmpty()) {
                metadata.put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                metadata.put("mandatoryWorkflowMissingRequiredInputs", missingRequiredInputs);
                observations.add("Mandatory workflow fallback did not invoke " + fallbackTool
                    + " because required inputs could not be proven from completed predecessor evidence: "
                    + String.join(", ", missingRequiredInputs) + ".");
                return;
            }
            Map<String, Object> originalArguments = new LinkedHashMap<>(fallbackArguments);
            ModelAssistedRetrievalBridge.EnrichmentResult enrichment =
                modelAssistedRetrievalBridge.enrichWithGate(
                activeChatModel,
                fallbackTool,
                fallbackArguments,
                new ModelAssistedRetrievalBridge.RetrievalEvidenceContext(query, Map.of())
            );
            fallbackArguments = enrichment.arguments();
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
            RetrievalQualityGate.Evaluation enhancedQuality = enrichment.qualityGate().isEmpty()
                ? null
                : RetrievalQualityGate.evaluate(execution.output(), enrichment.qualityGate());
            RetrievalQualityGate.Evaluation originalQuality = null;
            boolean originalSelected = false;
            if (enhancedQuality != null && !enhancedQuality.sufficient()) {
                ToolCallExecution originalExecution = executeToolCall(
                    fallbackTool,
                    originalArguments,
                    conversationId,
                    requestId,
                    userId,
                    tenantId,
                    tools,
                    Map.of(),
                    traces,
                    workflowStateTracker.attributesWithCompletedTools(runtimeAttributes, completedTools)
                );
                originalQuality = RetrievalQualityGate.evaluate(
                    originalExecution.output(), enrichment.qualityGate()
                );
                originalSelected = RetrievalQualityGate.preferFallback(enhancedQuality, originalQuality);
                ToolCallExecution nonSelected = originalSelected ? execution : originalExecution;
                if (nonSelected.trace() != null) {
                    traces.add(nonSelected.trace());
                }
                observations.add("Retrieval quality gate candidate " + nonSelected.observation());
                if (originalSelected) {
                    execution = originalExecution;
                }
                metadata.put("mandatoryRetrievalQualityGate:" + fallbackTool,
                    RetrievalQualityGate.report(enhancedQuality, originalQuality, originalSelected));
            } else if (enhancedQuality != null) {
                metadata.put("mandatoryRetrievalQualityGate:" + fallbackTool,
                    RetrievalQualityGate.report(enhancedQuality, null, false));
            }
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
            Map<String, Object> resultReview = mandatoryWorkflowResultReview(fallbackTool, execution.output());
            observations.add("Mandatory workflow local result review: " + stringify(resultReview));
            appendMandatoryWorkflowReview(metadata, resultReview);
            if (!Boolean.TRUE.equals(resultReview.get("satisfied"))) {
                metadata.put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                return;
            }
            runtimeAttributes = workflowStateTracker.attributesWithCompletedTools(
                runtimeAttributes,
                completedWorkflowToolsFromEvents(runtimeAttributes, workflowStateTracker.completedToolsFromTraces(traces))
            );
        }
        completedTools = completedWorkflowToolsFromEvents(
            runtimeAttributes,
            workflowStateTracker.completedToolsFromTraces(traces)
        );
        List<String> remainingMandatoryTools = workflowTools.missingMandatoryTools(mandatoryTools, completedTools);
        if (remainingMandatoryTools.isEmpty()) {
            metadata.put("mandatoryWorkflowRecoveredAfterPlan", true);
        } else {
            metadata.put("mandatoryWorkflowStillMissingAfterFallback", remainingMandatoryTools);
        }
    }

    private List<String> missingRequiredToolInputs(String toolName, Map<String, Object> arguments) {
        ToolMetadata toolMetadata = toolRegistry.getToolMetadata(toolName);
        if (toolMetadata == null || toolMetadata.getParameters() == null) {
            return List.of();
        }
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        List<String> missing = new ArrayList<>();
        for (ToolParameter parameter : toolMetadata.getParameters()) {
            if (parameter == null || !parameter.isRequired()
                || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            Object value = requiredToolInputValue(input, parameter.getName());
            if (value == null
                || value instanceof CharSequence text && text.toString().isBlank()
                || value instanceof Map<?, ?> map && map.isEmpty()
                || value instanceof java.util.Collection<?> collection && collection.isEmpty()) {
                missing.add(parameter.getName());
            }
        }
        return List.copyOf(missing);
    }

    private Object requiredToolInputValue(Map<String, Object> input, String parameterName) {
        Object direct = input.get(parameterName);
        if (direct != null) {
            return direct;
        }
        String normalized = parameterName.trim().toLowerCase(Locale.ROOT).replace("_", "");
        if ("template".equals(normalized) || "templateid".equals(normalized)) {
            return firstObject(input, "template", "templateId", "template_id");
        }
        if ("executioncontext".equals(normalized) || "mcpexecutioncontext".equals(normalized)) {
            return firstObject(input, "executionContext", "mcpExecutionContext", "execution_context");
        }
        if ("parameters".equals(normalized) || "params".equals(normalized)) {
            return firstObject(input, "parameters", "params");
        }
        return null;
    }

    private List<InteractionToolTrace> mandatoryPredecessorTraces(List<String> mandatoryTools,
                                                                  String fallbackTool,
                                                                  List<InteractionToolTrace> traces) {
        if (mandatoryTools == null || mandatoryTools.isEmpty()
            || fallbackTool == null || traces == null || traces.isEmpty()) {
            return List.of();
        }
        int fallbackIndex = -1;
        for (int index = 0; index < mandatoryTools.size(); index++) {
            if (toolNames.sameToolName(fallbackTool, mandatoryTools.get(index))) {
                fallbackIndex = index;
                break;
            }
        }
        if (fallbackIndex <= 0) {
            return List.of();
        }
        List<String> predecessors = mandatoryTools.subList(0, fallbackIndex);
        return traces.stream()
            .filter(Objects::nonNull)
            .filter(InteractionToolTrace::isSuccess)
            .filter(trace -> trace.getOutput() != null && !trace.getOutput().isBlank())
            .filter(trace -> predecessors.stream()
                .anyMatch(tool -> toolNames.sameToolName(tool, trace.getToolName())))
            .toList();
    }

    private Map<String, Object> mandatoryWorkflowResultReview(String toolName, ToolOutput output) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("schemaVersion", "mandatory_workflow_result_review.v1");
        review.put("toolName", toolName);
        review.put("reviewType", "LOCAL_CONTRACT_REVIEW");
        if (output == null || !output.isSuccess()) {
            review.put("satisfied", false);
            review.put("reason", "Mandatory workflow tool did not return a successful ToolOutput.");
            return review;
        }
        Integer templateCount = templateDiscoveryResultCount(toolName, output.getData());
        if (templateCount != null) {
            boolean satisfied = templateCount > 0;
            review.put("satisfied", satisfied);
            review.put("resultCode", satisfied ? "TEMPLATE_MATCHED" : "NO_MATCHING_TEMPLATE");
            review.put("returnedCount", templateCount);
            review.put("reason", satisfied
                ? "Template discovery returned at least one executable template."
                : "Template discovery completed but returned no executable template; dependent execution is blocked.");
            return review;
        }
        Map<String, Object> root = enterpriseMetadataResultRoot(output.getData(), 0);
        if (isEnterpriseMetadataResult(root)) {
            Map<String, Object> coverage = asMap(root.get("coverage"));
            Map<String, Object> sourceSchema = asMap(root.get("sourceSchema"));
            int sourceFieldCount = intValue(
                firstNonNull(root.get("sourceFieldCount"), sourceSchema.get("fieldCount")),
                collectionSize(sourceSchema.get("fields")));
            int matchedFieldCount = intValue(
                root.get("matchedFieldCount"),
                collectionSize(root.get("fieldMatches")));
            int processedFieldCount = intValue(
                coverage.get("processedFieldCount"),
                matchedFieldCount);
            boolean allFieldsProcessed = booleanValue(coverage.get("allFieldsProcessed"))
                || (sourceFieldCount > 0 && processedFieldCount == sourceFieldCount);
            boolean satisfied = sourceFieldCount > 0
                && matchedFieldCount == sourceFieldCount
                && allFieldsProcessed;
            review.put("satisfied", satisfied);
            review.put("reason", satisfied
                ? "Enterprise metadata contract returned and processed every source field."
                : "Enterprise metadata contract did not cover every source field.");
            review.put("sourceFieldCount", sourceFieldCount);
            review.put("matchedFieldCount", matchedFieldCount);
            review.put("processedFieldCount", processedFieldCount);
            review.put("allFieldsProcessed", allFieldsProcessed);
            String authoritativeEvidence =
                toolObservationBuilder.buildAuthoritativeExecutionEvidence(toolName, output);
            if (authoritativeEvidence != null && !authoritativeEvidence.isBlank()) {
                review.put("authoritativeEvidence", authoritativeEvidence);
            }
            return review;
        }
        review.put("satisfied", true);
        review.put("reason", "Mandatory workflow tool completed successfully and returned a terminal observation.");
        return review;
    }

    private boolean templateExecutionRetryRequested(InterpretationPlanRuntime.ExecutionResult result) {
        return result != null && result.steps() != null && result.steps().stream()
            .filter(Objects::nonNull)
            .map(InterpretationPlanRuntime.StepExecution::metadata)
            .filter(Objects::nonNull)
            .anyMatch(metadata -> Boolean.TRUE.equals(metadata.get("templateExecutionRetryRequested")));
    }

    private Map<String, Object> mandatoryWorkflowPredecessorReview(
        String fallbackTool,
        List<InteractionToolTrace> predecessorTraces
    ) {
        if (predecessorTraces == null || predecessorTraces.isEmpty()) {
            return Map.of("satisfied", true);
        }
        for (InteractionToolTrace trace : predecessorTraces) {
            if (trace == null) {
                continue;
            }
            ToolOutput predecessorOutput = trace.isSuccess()
                ? ToolOutput.success(asMap(trace.getOutput()))
                : ToolOutput.failure(firstNonBlank(trace.getErrorMessage(), "predecessor failed"));
            Map<String, Object> review = mandatoryWorkflowResultReview(trace.getToolName(), predecessorOutput);
            if (!Boolean.TRUE.equals(review.get("satisfied"))) {
                Map<String, Object> blocked = new LinkedHashMap<>(review);
                blocked.put("predecessorToolName", trace.getToolName());
                blocked.put("blockedDependentToolName", fallbackTool);
                return blocked;
            }
        }
        return Map.of("satisfied", true);
    }

    private Integer templateDiscoveryResultCount(String toolName, Object data) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        if (!(normalized.contains("template_query") || normalized.contains("template_search"))) {
            return null;
        }
        return templateDiscoveryResultCount(data, 0);
    }

    private Integer templateDiscoveryResultCount(Object value, int depth) {
        if (value == null || depth > 5) {
            return null;
        }
        if (value instanceof String text) {
            Map<String, Object> parsed = asMap(text);
            return parsed.isEmpty() ? null : templateDiscoveryResultCount(parsed, depth + 1);
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = asMap(raw);
        Integer explicit = integerValue(map.get("returnedCount"));
        if (explicit != null) {
            return explicit;
        }
        if (map.get("templates") instanceof java.util.Collection<?> templates) {
            return templates.size();
        }
        for (String key : List.of("structuredContent", "data", "result", "payload", "body", "output")) {
            Integer nested = templateDiscoveryResultCount(map.get(key), depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void appendMandatoryWorkflowReview(Map<String, Object> metadata,
                                               Map<String, Object> review) {
        if (metadata == null || review == null || review.isEmpty()) {
            return;
        }
        List<Map<String, Object>> reviews = metadata.get("mandatoryWorkflowResultReviews") instanceof List<?> existing
            ? new ArrayList<>((List<Map<String, Object>>) existing)
            : new ArrayList<>();
        reviews.add(Map.copyOf(review));
        metadata.put("mandatoryWorkflowResultReviews", List.copyOf(reviews));
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
        String observation,
        ToolOutput output
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
