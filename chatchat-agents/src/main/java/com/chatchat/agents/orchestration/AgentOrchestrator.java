package com.chatchat.agents.orchestration;

import com.chatchat.agents.evidence.normalization.EvidenceSource;

import com.chatchat.agents.evidence.graph.EvidenceGraph;

import com.chatchat.agents.orchestration.analysis.AnalysisContextPresentationContract;
import com.chatchat.agents.orchestration.analysis.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.AnalysisSummaryGovernanceBridge;
import com.chatchat.agents.orchestration.analysis.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.AnalysisTaskDispatcher;
import com.chatchat.agents.orchestration.analysis.AnalysisTaskProgress;
import com.chatchat.agents.orchestration.analysis.AnalysisTaskProgressReporter;
import com.chatchat.agents.orchestration.analysis.AnalysisTaskResult;
import com.chatchat.agents.orchestration.analysis.AnalysisWorkerRetryPolicy;
import com.chatchat.agents.orchestration.analysis.ContextCompressionEnvelope;
import com.chatchat.agents.orchestration.analysis.ContextTokenEstimator;
import com.chatchat.agents.orchestration.analysis.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.analysis.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.HierarchicalAnalysisReducer;
import com.chatchat.agents.orchestration.analysis.LocalAnalysisTaskDispatcher;
import com.chatchat.agents.orchestration.analysis.SemanticInsightContract;
import com.chatchat.agents.orchestration.analysis.SemanticInsightContractProvider;
import com.chatchat.agents.orchestration.analysis.StructuredDataProjector;
import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizer;
import com.chatchat.agents.orchestration.evidence.ContextEvidenceAggregator;
import com.chatchat.agents.orchestration.evidence.EvidenceTrustEvaluator;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.orchestration.model.DeadlineAwareChatModel;
import com.chatchat.agents.orchestration.planning.AgentContextBudget;
import com.chatchat.agents.orchestration.planning.AgentPlanBudgetPolicy;
import com.chatchat.agents.orchestration.planning.AgentRuntimeGuard;
import com.chatchat.agents.orchestration.planning.InterpretationPlanWorkflowGuard;
import com.chatchat.agents.orchestration.retrieval.McpParamBindingResolver;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedContextParameterBridge;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedRetrievalBridge;
import com.chatchat.agents.tool.RegistryMcpCapabilityHierarchy;
import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.orchestration.tool.McpAnalysisContextAdapter;
import com.chatchat.agents.orchestration.tool.ToolCallFingerprint;
import com.chatchat.agents.orchestration.tool.ToolObservationBuilder;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowStatePort;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowStateTracker;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowToolResolver;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.governance.McpEvidenceResult;
import com.chatchat.agents.runtime.run.AgentOutcomeProjection;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.assessment.RuntimeAnswerCandidate;
import com.chatchat.agents.assessment.TaskContract;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.answer.AgentAnswerReviewer;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.observation.AgentObservationPipeline;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunExecutor;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.agents.runtime.observation.AgentRuntimeFactGroundingContract;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.answer.DefaultAgentAnswerReviewer;
import com.chatchat.agents.runtime.observation.DefaultAgentObservationPipeline;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisPosition;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisSummaryProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;
import com.chatchat.common.runtime.protocol.RuntimeProtocolRegistry;
import com.chatchat.common.runtime.summary.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.ModelSummaryReducer;
import com.chatchat.agents.orchestration.protocol.RuntimeProtocolDefaults;
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
import com.chatchat.common.runtime.workflow.RuntimeWorkflowGuard;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelViolationException;
import com.chatchat.agents.runtime.plan.EvidenceBasedAssetCandidateEvaluator;
import com.chatchat.agents.runtime.plan.EvidenceBasedTemplateCandidateEvaluator;
import com.chatchat.agents.runtime.plan.RetrievalQualityGate;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.common.tool.McpToolNamePolicy;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

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
    private final AgentWorkflowDecisionPort workflowDecisionEngine;
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
    private ToolObservationBuilder toolObservationBuilder;
    private final AgentChatModelResolver chatModelResolver;
    private final AgentToolNameResolver toolNames;
    private final AgentToolArgumentResolver toolArguments;
    private final AgentWorkflowToolResolver workflowTools;
    private final ModelAssistedRetrievalBridge modelAssistedRetrievalBridge;
    private final ModelAssistedContextParameterBridge modelAssistedContextParameterBridge;
    private final AnswerCandidateCollector answerCandidateCollector = new AnswerCandidateCollector();
    private final AgentWorkflowStatePort workflowStateTracker;
    private final AgentAnswerFinalizer answerFinalizer;
    private final InterpretationPlanStore interpretationPlanStore;
    private final InterpretationPlanDagConverter interpretationPlanDagConverter = new InterpretationPlanDagConverter();
    private final RuntimeWorkflowGuard<InterpretationPlanWorkflowGuard.GuardContext,
        InterpretationPlanWorkflowGuard.GuardResult> interpretationPlanWorkflowGuard =
        new InterpretationPlanWorkflowGuard();
    private final EvidenceAugmentationPolicy evidenceAugmentationPolicy = new EvidenceAugmentationPolicy();
    private final AgentContextBudget contextBudget;
    private final int recordAnalysisChunkMaxChars;
    private final int recordAnalysisChunkMaxRows;
    private final int analysisSpillThresholdBytes;
    private final int analysisSummaryWorkerCount;
    private final int analysisSummaryWorkerMaxRetries;
    private final long analysisSummaryWorkerHeartbeatIntervalMs;
    private final long analysisSummaryWorkerHeartbeatTimeoutMs;
    private final ContextTokenEstimator contextTokenEstimator = new ContextTokenEstimator();
    private final ContextEvidenceAggregator contextEvidenceAggregator = new ContextEvidenceAggregator();
    private RuntimeAnalysisSummaryProtocol<AnalysisSummaryResult> analysisSummaryGovernanceBridge =
        RuntimeProtocolDefaults.analysisSummary();
    private RuntimeResultAnalysisProtocol evidenceGovernanceBridge =
        RuntimeProtocolDefaults.resultAnalysis();
    private ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
        HierarchicalAnalysisReducer.Result> hierarchicalAnalysisReducer =
        new HierarchicalAnalysisReducer();
    private final DeterministicInsightEngine deterministicInsightEngine =
        new DeterministicInsightEngine();
    private final StructuredDataProjector structuredDataProjector = new StructuredDataProjector();
    private final AnalysisWorkerRetryPolicy analysisWorkerRetryPolicy =
        new AnalysisWorkerRetryPolicy();
    private final HierarchicalAnalysisReducer workerDatasetReducer =
        new HierarchicalAnalysisReducer();
    private RuntimeAnalysisContextProtocol mcpAnalysisContextAdapter;
    private DagGovernanceContractProvider dagGovernanceContractProvider =
        DagGovernanceContractProvider.builtInFallback();
    private SemanticInsightContractProvider semanticInsightContractProvider =
        SemanticInsightContractProvider.disabled();
    private NodeAttemptStore nodeAttemptStore;
    private AnalysisEvidenceSpillStore analysisEvidenceSpillStore = AnalysisEvidenceSpillStore.disabled();
    private AnalysisTaskDispatcher analysisTaskDispatcher;

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
        this.workflowDecisionEngine = new AgentWorkflowDecisionEngine(toolRegistry);
        this.workflowStateTracker = new AgentWorkflowStateTracker(toolRegistry);
        this.toolRuntimeService = toolRuntimeService;
        this.objectMapper = objectMapper;
        this.mcpAnalysisContextAdapter = RuntimeProtocolDefaults.analysisContext(objectMapper);
        this.evidenceTrustEvaluator = evidenceTrustEvaluator == null ? new EvidenceTrustEvaluator() : evidenceTrustEvaluator;
        this.runStore = runStore == null ? new InMemoryAgentRunStore() : runStore;
        this.observationPipeline = observationPipeline == null ? new DefaultAgentObservationPipeline() : observationPipeline;
        AgentAnswerReviewer resolvedAnswerReviewer = answerReviewer == null ? new DefaultAgentAnswerReviewer(objectMapper) : answerReviewer;
        this.planner = new AgentPlanner(toolRegistry, objectMapper);
        this.runResultAdapter = new AgentRunResultAdapter(this.runStore, this.observationPipeline);
        this.toolObservationBuilder = new ToolObservationBuilder(this.evidenceTrustEvaluator);
        this.chatModelResolver = new AgentChatModelResolver(chatModel, modelsConfig);
        this.toolNames = new AgentToolNameResolver(new RegistryMcpCapabilityHierarchy(toolRegistry));
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
        AgentRuntimeProperties resolvedRuntimeProperties = agentRuntimeProperties == null
            ? new AgentRuntimeProperties()
            : agentRuntimeProperties;
        this.recordAnalysisChunkMaxChars = resolvedRuntimeProperties.recordAnalysisChunkMaxChars();
        this.recordAnalysisChunkMaxRows = resolvedRuntimeProperties.recordAnalysisChunkMaxRows();
        this.analysisSpillThresholdBytes = resolvedRuntimeProperties.analysisSpillThresholdBytes();
        this.analysisSummaryWorkerCount = resolvedRuntimeProperties.analysisSummaryWorkerCount();
        this.analysisSummaryWorkerMaxRetries =
            resolvedRuntimeProperties.analysisSummaryWorkerMaxRetries();
        this.analysisSummaryWorkerHeartbeatIntervalMs =
            resolvedRuntimeProperties.analysisSummaryWorkerHeartbeatIntervalMs();
        this.analysisSummaryWorkerHeartbeatTimeoutMs =
            resolvedRuntimeProperties.analysisSummaryWorkerHeartbeatTimeoutMs();
        this.analysisTaskDispatcher = new LocalAnalysisTaskDispatcher(
            this.analysisSummaryWorkerCount, this.analysisSummaryWorkerHeartbeatIntervalMs);
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

    /** Production supplies the database-backed semantic formula provider; default is fail-closed. */
    @Autowired(required = false)
    public void setSemanticInsightContractProvider(SemanticInsightContractProvider provider) {
        this.semanticInsightContractProvider = provider == null
            ? SemanticInsightContractProvider.disabled() : provider;
    }

    /** Production supplies the database-backed node attempt journal. */
    @Autowired(required = false)
    public void setNodeAttemptStore(NodeAttemptStore nodeAttemptStore) {
        this.nodeAttemptStore = nodeAttemptStore;
    }

    /** Production supplies the lossless RocksDB overflow/checkpoint store. */
    @Autowired(required = false)
    public void setAnalysisEvidenceSpillStore(AnalysisEvidenceSpillStore spillStore) {
        this.analysisEvidenceSpillStore = spillStore == null
            ? AnalysisEvidenceSpillStore.disabled()
            : spillStore;
    }

    /** Replaces local workers with a distributed task dispatcher without changing Driver orchestration. */
    @Autowired(required = false)
    public void setAnalysisTaskDispatcher(AnalysisTaskDispatcher dispatcher) {
        if (dispatcher != null) {
            this.analysisTaskDispatcher = dispatcher;
        }
    }

    /**
     * Executes an agent run through the stable runtime request/result contract.
     *
     * @param request the agent run request
     * @return the agent run result
     */
    @Override
    public AgentRunResult execute(AgentRunRequest request, KernelDataScope scope) {
        if (request == null) throw new IllegalArgumentException("Agent run request is required");
        if (scope == null) throw new IllegalArgumentException("Kernel data scope is required");
        requireScopeMatch("tenantId", request.getTenantId(), scope.tenantId());
        requireScopeMatch("requestId", request.getRequestId(), scope.requestId());
        requireScopeMatch("conversationId", request.getConversationId(), scope.conversationId());
        requireScopeMatch("runId", request.getRunId(), scope.runId());
        requireScopeMatch("userId", request.getUserId(), scope.userId());
        if (request.getTenantId() == null) request.setTenantId(scope.tenantId());
        if (request.getRequestId() == null) request.setRequestId(scope.requestId());
        if (request.getConversationId() == null) request.setConversationId(scope.conversationId());
        if (request.getRunId() == null) request.setRunId(scope.runId());
        if (request.getUserId() == null) request.setUserId(scope.userId());
        Map<String, Object> attributes = new LinkedHashMap<>(
            request.getAttributes() == null ? Map.of() : request.getAttributes());
        Map<String, Object> scopeProjection = new LinkedHashMap<>();
        putScopeValue(scopeProjection, "tenantId", scope.tenantId());
        putScopeValue(scopeProjection, "userId", scope.userId());
        putScopeValue(scopeProjection, "requestId", scope.requestId());
        putScopeValue(scopeProjection, "conversationId", scope.conversationId());
        putScopeValue(scopeProjection, "runId", scope.runId());
        putScopeValue(scopeProjection, "environment", scope.environment());
        scopeProjection.put("attributes", scope.attributes());
        attributes.put("kernelDataScope", Map.copyOf(scopeProjection));
        request.setAttributes(attributes);
        return execute(request);
    }

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
            if (runtimeResult.status() == AgentRunStatus.RUNNING) {
                AgentRun current = runStore.find(run.runId()).orElse(run);
                return runtimeResult.withStatusAndEvents(current.status(), current.events());
            }
            AgentRun completed = runStore.complete(run.runId(), runtimeResult);
            return runtimeResult.withStatusAndEvents(completed.status(), completed.events());
        } catch (AgentDeadlineExceededException ex) {
            AgentRun current = runStore.find(run.runId()).orElse(run);
            List<com.chatchat.agents.runtime.observation.AgentObservation> preservedObservations =
                runStore.observations(run.runId());
            List<com.chatchat.agents.runtime.plan.PlanStepCheckpoint> preservedCheckpoints =
                runStore.planStepCheckpoints(run.runId());
            long evidenceObservationCount = preservedObservations.stream()
                .filter(this::isEvidenceObservation)
                .count();
            boolean hasPreservedEvidence = evidenceObservationCount > 0
                || !preservedCheckpoints.isEmpty();
            String answer = hasPreservedEvidence
                ? "执行时间预算已耗尽；已完成步骤的证据和检查点均已保留，可从最近一致检查点继续执行。"
                : "";
            List<String> observationTexts = preservedObservations.stream()
                .map(com.chatchat.agents.runtime.observation.AgentObservation::content)
                .filter(Objects::nonNull)
                .filter(content -> !content.isBlank())
                .toList();
            Map<String, Object> deadlineMetadata = new LinkedHashMap<>();
            deadlineMetadata.put("stopReason", "time_budget_exhausted");
            deadlineMetadata.put("errorCode", "TIME_BUDGET_EXHAUSTED");
            deadlineMetadata.put("errorMessage", ex.getMessage());
            deadlineMetadata.put("completedEvidencePreservedAfterTimeout", hasPreservedEvidence);
            deadlineMetadata.put("preservedObservationCount", evidenceObservationCount);
            deadlineMetadata.put("preservedCheckpointCount", preservedCheckpoints.size());
            deadlineMetadata.put("observations", observationTexts);
            Map<String, Object> metadata = new com.chatchat.agents.runtime.run.AgentOutcomeProjection().enrich(
                deadlineMetadata,
                answer
            );
            AgentRunResult runtimeResult = AgentRunResult.builder()
                .runId(run.runId())
                .status(AgentRunStatus.COMPLETED)
                .answer(answer)
                .stopReason("time_budget_exhausted")
                .errorMessage(ex.getMessage())
                .steps(current.steps())
                .observations(preservedObservations)
                .metadata(metadata)
                .build();
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

    private void requireScopeMatch(String field, String requestValue, String scopeValue) {
        if (requestValue != null && !requestValue.isBlank() && scopeValue != null
            && !scopeValue.equals(requestValue)) {
            throw new KernelViolationException("KERNEL_SCOPE_MISMATCH",
                "Agent request " + field + " does not match Kernel scope");
        }
    }

    private void putScopeValue(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    /** Installs the Runtime OS protocol suite without coupling orchestration to implementations. */
    @Autowired(required = false)
    @SuppressWarnings("unchecked")
    public void setRuntimeProtocolRegistry(RuntimeProtocolRegistry registry) {
        if (registry == null) return;
        this.evidenceGovernanceBridge = registry.require(RuntimeResultAnalysisProtocol.class);
        this.mcpAnalysisContextAdapter = registry.require(RuntimeAnalysisContextProtocol.class);
        this.analysisSummaryGovernanceBridge =
            (RuntimeAnalysisSummaryProtocol<AnalysisSummaryResult>) (RuntimeAnalysisSummaryProtocol<?>)
                registry.require(RuntimeAnalysisSummaryProtocol.class);
        this.analysisTaskDispatcher = (AnalysisTaskDispatcher) (ModelSummaryDispatcher<?, ?, ?>)
            registry.require(ModelSummaryDispatcher.class);
        this.hierarchicalAnalysisReducer =
            (ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
                HierarchicalAnalysisReducer.Result>) (ModelSummaryReducer<?, ?, ?>)
                    registry.require(ModelSummaryReducer.class);
        this.toolObservationBuilder = new ToolObservationBuilder(
            this.evidenceTrustEvaluator, this.evidenceGovernanceBridge);
        this.answerFinalizer.setAnalysisSummaryProtocol(this.analysisSummaryGovernanceBridge);
    }

    private boolean isEvidenceObservation(com.chatchat.agents.runtime.observation.AgentObservation observation) {
        if (observation == null || observation.type() == null) {
            return false;
        }
        String type = observation.type().trim().toLowerCase(Locale.ROOT);
        return "tool".equals(type)
            || "tool_failure".equals(type)
            || "batch".equals(type)
            || "batch_tool".equals(type);
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
        if (request.getSkillId() != null && !request.getSkillId().isBlank()) {
            attributes.putIfAbsent("agentId", request.getSkillId().trim());
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
        requestRuntimeAttributes.put("boundDocumentIds", documentIds);
        requestRuntimeAttributes.put("boundDocumentTags", documentTags);
        if (documentSearchTool != null && !documentSearchTool.isBlank()) {
            requestRuntimeAttributes.put("documentSearchTool", documentSearchTool);
        }
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
        activeChatModel = new DeadlineAwareChatModel(
            activeChatModel,
            () -> runtimeGuard.remainingTimeMs(requestRuntimeAttributes)
        );
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
            if (decision.interpretationPlan() != null
                && decision.interpretationPlan().executionPolicy() != null) {
                metadata.put("modelDeclaredLatencyBudgetMs",
                    decision.interpretationPlan().executionPolicy().latencyBudgetMs());
                metadata.put("modelDeclaredLatencyBudgetAdvisory", true);
                metadata.put("remainingTimeMs", runtimeGuard.remainingTimeMs(requestRuntimeAttributes));
            }
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

            boolean authoritativeRuntimeFallback = decision.interpretationPlan() != null
                && "invalid_interpretation_plan".equals(decision.reason())
                && !authoritativeWorkflowDag.isEmpty()
                && !plannerMandatoryTools.isEmpty();
            if (authoritativeRuntimeFallback) {
                metadata.put("authoritativeRuntimeFallbackAfterInvalidPlan", true);
                metadata.put("authoritativeRuntimeFallbackStep", step);
                metadata.put("authoritativeRuntimeFallbackPendingTools", List.copyOf(plannerMandatoryTools));
                Map<String, Map<String, Object>> candidateInputs =
                    authoritativeWorkflowCandidateInputs(decision.interpretationPlan(), authoritativeWorkflowDag);
                if (!candidateInputs.isEmpty()) {
                    requestRuntimeAttributes.put("authoritativeWorkflowCandidateInputs", candidateInputs);
                    metadata.put("authoritativeWorkflowCandidateInputTools", List.copyOf(candidateInputs.keySet()));
                }
                observations.add("Planner candidate remained invalid after authoritative DAG topology repair. "
                    + "Runtime stopped model replanning and delegated the pending Ready workflow tools to the "
                    + "deterministic Java scheduler: " + plannerMandatoryTools + ".");
                break;
            }

            if (decision.interpretationPlan() != null
                && Boolean.TRUE.equals(decision.executionPlan().get("interpretationPlanValid"))) {
                Map<String, Map<String, Object>> candidateInputs =
                    authoritativeWorkflowCandidateInputs(decision.interpretationPlan(), authoritativeWorkflowDag);
                if (!candidateInputs.isEmpty()) {
                    requestRuntimeAttributes.put("authoritativeWorkflowCandidateInputs", candidateInputs);
                    metadata.put("authoritativeWorkflowCandidateInputTools", List.copyOf(candidateInputs.keySet()));
                }
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

        if (requireToolBeforeFinal && traces.isEmpty() && authoritativeWorkflowDag.isEmpty()) {
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
                maxToolCalls,
                systemPrompt,
                cancellationCheck
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
        Map<String, Object> authoritativeWorkflowDagRepair = Map.of();
        if (hasAuthoritativeWorkflowDag) {
            InterpretationPlanOptimizer.OptimizationResult workflowDagOptimization =
                new InterpretationPlanOptimizer(toolRegistry).optimize(plan, authoritativeWorkflowDag);
            plan = workflowDagOptimization.plan() == null ? plan : workflowDagOptimization.plan();
            authoritativeWorkflowDagPasses = workflowDagOptimization.appliedPasses();
            authoritativeWorkflowDagRepair = workflowDagOptimization.repairResult().auditMetadata();
        }
        metadata.put("interpretationPlanPipeline", true);
        metadata.put("interpretationPlanVersion", plan.version());
        metadata.put("authoritativeWorkflowDagPasses", authoritativeWorkflowDagPasses);
        if (!authoritativeWorkflowDagRepair.isEmpty()) {
            metadata.put("authoritativeWorkflowDagRepair", authoritativeWorkflowDagRepair);
        }
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
            new InterpretationPlanOptimizer(toolRegistry),
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
            InterpretationPlanRuntime.ExecutionRequest executionRequest = planExecutionRequest(
                plan,
                tenantId,
                requestId,
                conversationId,
                userId,
                tools,
                workflowAttemptAttributes(runtimeAttributes, 0)
            );
            firstResult = runtime.execute(executionRequest,
                planKernelScope(tenantId, userId, requestId, conversationId, runtimeAttributes));
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
            new InterpretationPlanWorkflowGuard.GuardContext(
                plan,
                firstResult,
                metadataStringList(metadata, "mandatoryTools"),
                metadataStringList(runtimeAttributes, "workflowCompletedTools")
            ));
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
        boolean actionableEvidenceRefinementAvailable =
            !evidenceRefinementRequiredTools(evidenceHistory, tools).isEmpty();
        boolean augmentationOverrideAvailable = configuredMaxRewriteTimes == 0
            && (firstEvidenceAvailable || actionableEvidenceRefinementAvailable)
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
        boolean templateExecutionRetryRequested = templateExecutionRetryRequested(firstResult);
        int maxRewriteTimes = initialRewriteLimit(
            configuredMaxRewriteTimes,
            latestAugmentationDecision,
            augmentationOverrideAvailable,
            executionRecoveryRequired,
            templateExecutionRetryRequested,
            tools != null && !tools.isEmpty()
        );
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
        if (templateExecutionRetryRequested
            && latestAugmentationDecision.continueLoop()
            && tools != null && !tools.isEmpty()) {
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
                currentResult,
                evidenceHistory
            );
            observations.add(rewriteSummary);
            InterpretationPlan.Step failedStep = repairRootStep(currentPlan, currentResult);
            String repairReason = evidenceRewriteReason(currentResult, evidenceHistory);
            Map<String, Object> repairEvidenceContext = repairEvidenceContext(evidenceHistory);
            metadata.put("latestDagRepairEvidenceContext", repairEvidenceContext);
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
            Map<String, Object> authoritativeRewriteRepair = Map.of();
            Object rewriteWorkflowDag = authoritativeWorkflowDagForContinuation(
                authoritativeWorkflowDag,
                rewrittenPlan,
                completedTools
            );
            if (rewrittenPlan != null && hasAuthoritativeWorkflowDag) {
                InterpretationPlanOptimizer.OptimizationResult authoritativeRewrite =
                    new InterpretationPlanOptimizer(toolRegistry).optimize(rewrittenPlan, rewriteWorkflowDag);
                rewrittenPlan = authoritativeRewrite.plan() == null ? rewrittenPlan : authoritativeRewrite.plan();
                authoritativeRewritePasses = authoritativeRewrite.appliedPasses();
                authoritativeRewriteRepair = authoritativeRewrite.repairResult().auditMetadata();
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
            if (!authoritativeRewriteRepair.isEmpty()) {
                metadata.put("authoritativeWorkflowRewriteRepair", authoritativeRewriteRepair);
            }
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
            InterpretationPlanRuntime.ExecutionRequest rewriteRequest = planExecutionRequest(
                currentPlan,
                tenantId,
                requestId,
                conversationId,
                userId,
                tools,
                rewriteExecutionAttributes
            );
            currentResult = runtime.execute(rewriteRequest,
                planKernelScope(tenantId, userId, requestId, conversationId, rewriteExecutionAttributes));
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
                new InterpretationPlanWorkflowGuard.GuardContext(
                    currentPlan,
                    currentResult,
                    metadataStringList(metadata, "mandatoryTools"),
                    metadataStringList(runtimeAttributes, "workflowCompletedTools")
                ));
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
            int revisedEvidenceLimit = evidenceDrivenRewriteLimit(
                configuredMaxRewriteTimes,
                latestAugmentationDecision,
                evidenceHistory,
                tools
            );
            if (revisedEvidenceLimit > maxRewriteTimes) {
                maxRewriteTimes = revisedEvidenceLimit;
                metadata.put("evidenceDrivenRewriteBudgetApplied", true);
                metadata.put("evidenceDrivenRewriteBudgetExpandedAfterDiscovery", true);
                metadata.put("interpretationPlanMaxRewriteTimes", maxRewriteTimes);
                metadata.put("evidenceDrivenRewriteBudgetReason",
                    "A completed discovery step exposed an available execution tool for the remaining evidence gap.");
                observations.add("InterpretationPlan retained another bounded evidence round because discovery "
                    + "returned an actionable tool that is present in the pinned runtime registry.");
            }
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
            maxToolCalls,
            systemPrompt,
            cancellationCheck
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
                || (!(ex instanceof AgentDeadlineExceededException)
                    && !message.toLowerCase(Locale.ROOT).contains("timed out"))) {
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
        List<String> failedMandatoryTools = missingMandatoryTools.stream()
            .filter(tool -> workflowTools.hasToolTrace(traces, tool))
            .toList();
        List<String> pendingMandatoryTools = missingMandatoryTools.stream()
            .filter(tool -> !workflowTools.hasToolTrace(traces, tool))
            .filter(tool -> workflowTools.hasAnyToolTrace(traces, tool))
            .toList();
        List<String> unattemptedMandatoryTools = missingMandatoryTools.stream()
            .filter(tool -> !workflowTools.hasAnyToolTrace(traces, tool))
            .toList();
        String workflowErrorCode = !failedMandatoryTools.isEmpty()
            ? "MANDATORY_TOOL_EXECUTION_FAILED"
            : (!pendingMandatoryTools.isEmpty()
                ? "MANDATORY_TOOL_CONFIRMATION_PENDING"
                : "MANDATORY_TOOL_NOT_SCHEDULED");
        metadata.put("errorCode", workflowErrorCode);
        metadata.put("errorMessage", workflowErrorCode + ": " + reason
            + " Unsuccessful mandatory tools: " + missingMandatoryTools);
        metadata.put("missingMandatoryTools", missingMandatoryTools);
        metadata.put("failedMandatoryTools", failedMandatoryTools);
        metadata.put("pendingMandatoryTools", pendingMandatoryTools);
        metadata.put("unattemptedMandatoryTools", unattemptedMandatoryTools);
        metadata.put("terminalMandatoryTools", failedMandatoryTools);
        Map<String, Object> mandatoryToolStates = new LinkedHashMap<>();
        for (String tool : mandatoryTools) {
            boolean successful = workflowTools.missingMandatoryTools(List.of(tool), completedTools).isEmpty();
            boolean failed = failedMandatoryTools.contains(tool);
            boolean pending = pendingMandatoryTools.contains(tool);
            mandatoryToolStates.put(tool, Map.of(
                "attempted", successful || failed || pending,
                "terminal", successful || failed,
                "successful", successful,
                "evidenceAccepted", successful
            ));
        }
        boolean mandatoryWorkflowTerminal = pendingMandatoryTools.isEmpty()
            && unattemptedMandatoryTools.isEmpty();
        metadata.put("mandatoryToolStates", mandatoryToolStates);
        metadata.put("mandatoryWorkflowTerminal", mandatoryWorkflowTerminal);
        metadata.put("mandatoryWorkflowCompleted", false);
        metadata.put("mandatoryWorkflowPending", !mandatoryWorkflowTerminal);
        observations.add(reason + " Unsuccessful mandatory tools: " + missingMandatoryTools);
        observations.add(workflowErrorCode + ": failed=" + failedMandatoryTools
            + ", pending=" + pendingMandatoryTools
            + ", unattempted=" + unattemptedMandatoryTools + ".");
        metadata.put("failureSummaryRequiresToolCompletionContext", true);
        metadata.put("deterministicMandatoryWorkflowFailure", true);
        String workflowContractError = stringValue(metadata.get("mandatoryWorkflowContractError"));
        if (workflowContractError != null && !workflowContractError.isBlank()) {
            String contractFailure = "必需工具 " + String.join(", ", missingMandatoryTools)
                + " 未执行：模板发现结果没有提供与该执行器兼容的运行时合同。"
                + " 已完成的工具证据均已保留；请检查工作流必需工具配置或维护匹配的模板。"
                + " 技术原因：" + workflowContractError;
            return answerFinalizer.finishExecution(contractFailure, traces, metadata, observations);
        }
        List<String> failureParts = new ArrayList<>();
        if (!failedMandatoryTools.isEmpty()) {
            failureParts.add("必需工具 " + String.join(", ", failedMandatoryTools) + " 已执行并失败");
        }
        if (!pendingMandatoryTools.isEmpty()) {
            failureParts.add("必需工具 " + String.join(", ", pendingMandatoryTools) + " 正在等待确认");
        }
        if (!unattemptedMandatoryTools.isEmpty()) {
            failureParts.add("必需工具 " + String.join(", ", unattemptedMandatoryTools)
                + " 尚未调度或因前置依赖失败而跳过");
        }
        String deterministicFailure = String.join("；", failureParts)
            + "。本次工作流未满足必需证据条件。"
            + " 已完成的工具证据和失败原因均已保留，可在修复失败节点后继续诊断。";
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

    private KernelDataScope planKernelScope(String tenantId, String userId, String requestId,
                                            String conversationId, Map<String, Object> attributes) {
        Map<String, Object> values = attributes == null ? Map.of() : attributes;
        String runId = stringValue(values.get(AGENT_RUN_ID_ATTRIBUTE));
        String environment = stringValue(values.get("agentRuntimeEnvironment"));
        return new KernelDataScope(tenantId, userId, requestId, conversationId, runId, environment, values);
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
        executionAttributes.put("toolRegistryRevision", toolRegistry == null ? 0L : toolRegistry.getRevision());
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
        log.info("agentModelRequest phase=interpretation_plan_dag_decision decisionCount={} purpose={} promptChars={} readyStepCount={} remainingStepCount={} completedStepCount={} modelClass={}",
            request.decisionCount(),
            request.decisionPurpose(),
            prompt.length(),
            request.readyStepIds() == null ? 0 : request.readyStepIds().size(),
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
        metadata.put("decisionPurpose", request.decisionPurpose());
        metadata.put("readyStepIds", request.readyStepIds() == null ? List.of() : request.readyStepIds());
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
        prompt.append("Java Runtime has already computed the authoritative Ready node set. ")
            .append("Your role is limited to semantic arbitration among those legal candidates; Java owns ordinary scheduling.\n");
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
        prompt.append("- Select only step ids from ready_step_ids. remaining_step_ids is context, not an executable candidate set.\n");
        prompt.append("- Never select, suggest, or substitute a node outside ready_step_ids. Runtime will reject it even when its dependencies appear satisfiable to you.\n");
        prompt.append("- When decision_purpose is SEMANTIC_BRANCH_ARBITRATION, select exactly one Ready candidate. Unselected alternatives are recorded as skipped semantic branches.\n");
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
        prompt.append("decision_purpose: ").append(request.decisionPurpose()).append("\n");
        prompt.append("ready_step_ids: ").append(request.readyStepIds() == null ? List.of() : request.readyStepIds()).append("\n");
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
        List<InterpretationPlanRuntime.ExecutionResult> resolvedAttemptResults =
            resolvedSummaryEvidenceAttempts(attemptResults);
        InterpretationPlanRuntime.ExecutionResult resolvedResult = resolvedAttemptResults.isEmpty()
            ? result
            : resolvedAttemptResults.get(resolvedAttemptResults.size() - 1);
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
                resolvedResult,
                resolvedAttemptResults,
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
            if (ex instanceof AgentDeadlineExceededException deadlineExceeded) {
                throw deadlineExceeded;
            }
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

    private List<InterpretationPlanRuntime.ExecutionResult> resolvedSummaryEvidenceAttempts(
        List<InterpretationPlanRuntime.ExecutionResult> attempts
    ) {
        if (attempts == null || attempts.isEmpty()) {
            return List.of();
        }
        List<InterpretationPlanRuntime.ExecutionResult> resolvedAttempts = new ArrayList<>(attempts.size());
        for (InterpretationPlanRuntime.ExecutionResult attempt : attempts) {
            if (attempt == null || attempt.steps() == null || attempt.steps().isEmpty()) {
                resolvedAttempts.add(attempt);
                continue;
            }
            boolean changed = false;
            List<InterpretationPlanRuntime.StepExecution> resolvedSteps = new ArrayList<>(attempt.steps().size());
            for (InterpretationPlanRuntime.StepExecution step : attempt.steps()) {
                Object resolved = resolvedEvidenceData(step);
                if (step == null || resolved == step.output()) {
                    resolvedSteps.add(step);
                    continue;
                }
                changed = true;
                resolvedSteps.add(new InterpretationPlanRuntime.StepExecution(
                    step.stepId(), step.actionType(), step.toolName(), step.success(), resolved,
                    step.errorMessage(), step.toolExecution(), step.finalAnswer(), step.durationMs(), step.metadata()
                ));
            }
            resolvedAttempts.add(changed
                ? new InterpretationPlanRuntime.ExecutionResult(
                    attempt.status(), attempt.success(), attempt.approvalRequired(), attempt.errorMessage(),
                    attempt.finalAnswer(), List.copyOf(resolvedSteps), attempt.metadata(), attempt.durationMs())
                : attempt);
        }
        return List.copyOf(resolvedAttempts);
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
            "evidenceTraceComplete", coverage.evidenceTraceComplete(),
            "sourceContentComplete", coverage.sourceContentComplete(),
            "iterationCount", coverage.iterations(),
            "summaryResultCount", coverage.summaryResults().size(),
            "rawReplayChunkCount", coverage.rawReplayChunkCount()
        );
        AnalysisSummaryResult summaryResult = analysisSummaryGovernanceBridge.finalResult(
            summaryIsolationScope(coverage, metadata),
            stage, content, outcome, coverageMap, coverage.synthesisInputs());
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

    String interpretationPlanReviewEvidenceContext(String synthesisPrompt) {
        if (synthesisPrompt == null || synthesisPrompt.isBlank()) {
            return "";
        }
        int start = synthesisPrompt.indexOf("Authoritative Summary Evidence Ledger (runtime-generated):");
        if (start >= 0) {
            int end = synthesisPrompt.indexOf("\nStored RunStore/RocksDB observations:", start);
            if (end < 0) {
                end = synthesisPrompt.indexOf("\nIn-memory observations:", start);
            }
            if (end < 0) {
                end = synthesisPrompt.lastIndexOf("\nReturn only the final user-facing Markdown answer");
            }
            if (end < 0) {
                end = synthesisPrompt.length();
            }
            if (end > start) {
                String context = "Reviewer evidence contract: the ledger classifies execution state, while the "
                    + "executed-plan evidence below contains the authoritative returned values. Review both; "
                    + "never infer that values are missing merely because the ledger is a compact index.\n"
                    + synthesisPrompt.substring(start, end);
                return appendRecordGroundedReviewerEvidence(context, synthesisPrompt);
            }
        }
        start = synthesisPrompt.indexOf("Executed plan attempts (");
        if (start < 0) {
            return "";
        }
        int end = synthesisPrompt.lastIndexOf("\nReturn only the final user-facing Markdown answer");
        if (end <= start) {
            end = synthesisPrompt.length();
        }
        return appendRecordGroundedReviewerEvidence(synthesisPrompt.substring(start, end), synthesisPrompt);
    }

    private String appendRecordGroundedReviewerEvidence(String context, String synthesisPrompt) {
        String marker = "Complete returned-record evidence (record_grounded_analysis.v1).";
        int recordEvidenceStart = synthesisPrompt.indexOf(marker);
        if (recordEvidenceStart < 0) {
            return context;
        }
        String recordEvidence = synthesisPrompt.substring(recordEvidenceStart).trim();
        if (recordEvidence.isBlank() || context.contains(recordEvidence)) {
            return context;
        }
        return context + "\n\nReviewer returned-record evidence (authoritative analyzed values):\n"
            + recordEvidence;
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
        prompt.append("You are the final step-by-step answer synthesizer for Agent Runtime InterpretationPlan attempts.\n");
        prompt.append("Answer the user in Chinese using only the executed attempt records, model review decisions, and stored observations.\n");
        prompt.append("Return a polished Markdown document, not a single plain paragraph. Use concise headings and lists when they improve readability.\n");
        prompt.append("Do not wrap the Markdown in code fences and do not output JSON.\n");
        prompt.append("Primary answer contract (outcome-first):\n");
        prompt.append("- Start by directly answering the user's requested deliverable. For a summary request, synthesize the document's main content and value points first.\n");
        prompt.append("- Use this answer order unless the user requests another format: direct conclusion; evidence-backed findings grouped by the user's requested dimensions; coverage and material limitations; smallest useful next action only when evidence is incomplete.\n");
        prompt.append("- Do not begin with asset discovery, template counts, tool calls, plan attempts, or execution chronology. Include execution details only when they explain evidence coverage, failure, or provenance requested by the user.\n");
        prompt.append("- Do not make the tool evidence list, document heading path, execution trace, or JSON field names the body of the answer.\n");
        prompt.append("- Data analysis is the deliverable; evidence plumbing is not. Unless the user explicitly asks for provenance, audit details, API debugging, citations, or commands, do not create sections for evidence chains, API endpoints, tool calls, execution facts, sources, diagnostic workflow, verification commands, or manual troubleshooting, and do not expose internal tool://, doc://, web://, evidenceId, or [evidence: ...] markers. Evidence remains available in runtime metadata.\n");
        prompt.append("- For an analysis request, the opening conclusion and each major finding must state concrete returned values, comparisons, distributions, or anomalies and explain what they mean. HTTP success, endpoint availability, query completion, row counts, and coverage percentages are operational metadata, not analytical findings.\n");
        prompt.append("- Derive the analysis dimensions, comparisons, and report structure only from the user's request, the returned schema and values, and supplied analysisContext. Do not impose a domain-specific analytical framework or a canned report template that is not established by those inputs.\n");
        prompt.append("- Treat returned business data as the analysis subject. Source, citation, trust, tool, and execution metadata are internal grounding support and must not displace analysis of the returned data unless the user explicitly requests provenance or runtime diagnostics.\n");
        prompt.append("- Do not replace analysis with a catalogue of what could be calculated or with repeated statements that values need expansion. If returned values exist, analyze them now. Mention a material limitation once, after the supported findings, and only for conclusions that the missing data actually blocks.\n");
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
        prompt.append("- The final answer must be grounded in cumulative executed tool results from every iteration, regardless of source type. Do not treat an intermediate model conclusion as evidence unless its referenced evidenceId exists in an executed tool result.\n");
        prompt.append("- System instructions and conversation history provide behavior and context only. A documentId, timestamp, metric, command output, or concrete result appearing there is not current-turn evidence unless the executed attempts below return the same value.\n");
        prompt.append("- Resolve conflicts explicitly. If three iterations still leave a material gap, report that gap instead of filling it with model knowledge.\n");
        prompt.append("- Do not hide earlier partial or failed attempts when they contain usable evidence. State unresolved limitations after considering all attempts.\n");
        prompt.append("- diagnosticRun is internal completeness metadata, not the report subject. Use completed checks to produce data findings first. Only when an incomplete check materially limits the requested conclusion, mention that check and its exact runtime reason once in a short limitations paragraph after the analysis. Do not present diagnostic counts, coverage ratios, required-check tables, or optional-check inventories unless the user explicitly requests execution coverage or audit details.\n");
        prompt.append("- A missing diagnostic child with no ToolCallResult is NOT_EXECUTED. Do not speculate that it timed out, hit resource contention, lacked permissions, or failed remotely unless a child result explicitly records that status/reason.\n");
        prompt.append("- Do not recommend manual one-by-one execution as the product solution when an ordered runtime batch is expected. Report the missing batch dispatch/evidence and recommend repairing or retrying the batch workflow.\n");
        prompt.append("- For batch_execution_evidence.v1, enforce resultSetContract.mode=ONE_TEMPLATE_ONE_RESULT_SET: every results[] item is one independently addressable template result set identified by resultSetId and templateId. Never merge rows from different templates before interpreting their individual semantics. Preserve successful empty result sets as facts when the template contract defines empty as success. results[].dataset.rows or results[].datasets[].rows contains the complete returned structured rows; path identifies the original nested JSON location. For non-tabular child results, results[].analysisProjection.datasets[].records contains the Runtime evidence bridge's protocol-governed analysis records. Analyze these values directly. Row and stream coverage is losslessly processed through record_grounded_analysis.v1 model chunk summaries, never capped or sampled. Never reduce a successful non-empty batch to execution metadata or claim that concrete values are unavailable when dataset rows or analysis projection records are present.\n");
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
        prompt.append("Authoritative Summary Evidence Ledger (runtime-generated):\n")
            .append("- This ledger is the factual boundary for final synthesis and reviewer rewrites. "
                + "Every factual finding must be supported by an EXECUTED_RESULT entry and its evidenceId.\n")
            .append("- DISCOVERY_ONLY entries establish available assets/templates only; they never prove the requested business, diagnostic, or health finding.\n")
            .append("- BLOCKED_PRE_EXECUTION and FAILED entries may explain a limitation only. They never prove a remote execution, returned metric, or target state.\n")
            .append("- PARTIAL_PREVIEW means Runtime could not resolve the complete result. It may support only facts visibly present in the preview; absence from a preview never proves absence in the target.\n")
            .append("- A result set absent from this ledger must be described as not available, never reconstructed from template text, review notes, or prior model wording.\n")
            .append(stringify(summaryEvidenceLedger(results)))
            .append("\n\n");
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
                        prompt.append("  toolResult (complete Runtime input):\n")
                            .append("  authoritativeToolResultEvidence (runtime evidence projection; operation inputs omitted):\n")
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
                    String metadataEvidence = stringify(summaryObservationMetadata(observation.metadata()));
                    prompt.append("  metadata: ")
                        .append(compressionEnabled
                            ? shortObservationText(metadataEvidence, SUMMARY_COMPRESSED_OBSERVATION_CHARS)
                            : metadataEvidence)
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


    private List<Map<String, Object>> summaryEvidenceLedger(
        List<InterpretationPlanRuntime.ExecutionResult> results
    ) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<InterpretationPlanRuntime.ExecutionResult> safeResults = results == null ? List.of() : results;
        for (int attemptIndex = 0; attemptIndex < safeResults.size(); attemptIndex++) {
            InterpretationPlanRuntime.ExecutionResult attempt = safeResults.get(attemptIndex);
            if (attempt == null || attempt.steps() == null) {
                continue;
            }
            for (InterpretationPlanRuntime.StepExecution step : attempt.steps()) {
                if (step == null) {
                    continue;
                }
                String evidenceId = "iteration:" + (attemptIndex + 1) + ":step:" + step.stepId()
                    + ":tool:" + firstNonBlank(step.toolName(), step.actionType());
                if (step.output() instanceof ToolCallBatchResult batch) {
                    for (ToolCallResult child : batch.results() == null ? List.<ToolCallResult>of() : batch.results()) {
                        if (child == null) {
                            continue;
                        }
                        Map<String, Object> entry = baseSummaryLedgerEntry(
                            attemptIndex, step, firstNonBlank(child.evidenceId(), evidenceId),
                            "SUCCESS".equalsIgnoreCase(child.status()), stringify(child.error()));
                        entry.put("templateId", firstNonBlank(child.templateId(), child.templateCode()));
                        entry.put("callId", child.callId());
                        entry.put("resultStatus", child.status());
                        entry.put("commandContext", metadataOf(
                            "toolName", child.toolName(),
                            "normalizedToolName", child.normalizedToolName(),
                            "templateId", firstNonBlank(child.templateId(), child.templateCode()),
                            "assetId", child.assetId(),
                            "assetDisplayName", child.assetDisplayName(),
                            "sequence", child.sequence()
                        ));
                        entry.put("commandDescription", describeBatchChildCommand(child));
                        entry.put("references", metadataOf(
                            "relationType", "BATCH_MEMBER_OF",
                            "parentEvidenceId", evidenceId,
                            "batchId", child.batchId(),
                            "callId", child.callId(),
                            "sequence", child.sequence()
                        ));
                        if ("SUCCESS".equalsIgnoreCase(child.status())) {
                            if (summaryPreviewOnly(child.output())) {
                                entry.put("executionState", "PARTIAL_PREVIEW");
                            }
                            entry.put("returnedEvidence", summaryLedgerOutput(child.output()));
                        }
                        entries.add(entry);
                    }
                    continue;
                }
                Map<String, Object> entry = baseSummaryLedgerEntry(
                    attemptIndex, step, evidenceId, step.success(), step.errorMessage());
                if (step.success() && step.output() != null) {
                    if (summaryPreviewOnly(step.output())) {
                        entry.put("executionState", "PARTIAL_PREVIEW");
                    }
                    entry.put("returnedEvidence", summaryLedgerOutput(step.output()));
                }
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private String describeBatchChildCommand(ToolCallResult child) {
        if (child == null) {
            return "No executable command metadata was returned.";
        }
        String executor = firstNonBlank(firstNonBlank(child.toolName(), child.normalizedToolName()), "unknown executor");
        String template = firstNonBlank(firstNonBlank(child.templateId(), child.templateCode()), "unidentified template");
        String asset = firstNonBlank(firstNonBlank(child.assetDisplayName(), child.assetId()), "unidentified target");
        return "Executor " + executor + " invoked template " + template + " for target " + asset + ".";
    }

    private boolean summaryPreviewOnly(Object output) {
        Map<String, Object> value = objectMap(output);
        return Boolean.TRUE.equals(booleanValue(value.get("summaryTruncated")))
            || Boolean.TRUE.equals(booleanValue(value.get("outputTruncated")));
    }

    private Map<String, Object> baseSummaryLedgerEntry(int attemptIndex,
                                                        InterpretationPlanRuntime.StepExecution step,
                                                        String evidenceId,
                                                        boolean success,
                                                        String error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("evidenceId", evidenceId);
        entry.put("attempt", attemptIndex + 1);
        entry.put("stepId", step.stepId());
        entry.put("tool", firstNonBlank(step.toolName(), step.actionType()));
        entry.put("executionState", summaryLedgerExecutionState(step, success));
        if (error != null && !error.isBlank()) {
            entry.put("runtimeReason", error);
        }
        return entry;
    }

    private String summaryLedgerExecutionState(InterpretationPlanRuntime.StepExecution step,
                                               boolean success) {
        if (!success) {
            return step.toolExecution() == null ? "BLOCKED_PRE_EXECUTION" : "FAILED";
        }
        if (step != null && "final_answer".equalsIgnoreCase(step.actionType())) {
            return "SYNTHESIS_HINT";
        }
        String normalizedTool = firstNonBlank(step == null ? null : step.toolName(), "")
            .toLowerCase(Locale.ROOT);
        return normalizedTool.contains("asset_query") || normalizedTool.contains("template_query")
            ? "DISCOVERY_ONLY" : "EXECUTED_RESULT";
    }

    private Object summaryLedgerOutput(Object output) {
        Map<String, Object> facts = structuredOutputFacts(output);
        if (!facts.isEmpty()) {
            return facts;
        }
        return shortObservationText(stringify(contextEvidenceAggregator.aggregate(output)), 4000);
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
        List<Map<String, Object>> excludedDatasets = excludedExecutionDatasets(result);
        if (metadata != null) {
            metadata.put("recordAnalysisExcludedDatasets", excludedDatasets);
            metadata.put("recordAnalysisExcludedDatasetCount", excludedDatasets.size());
        }
        for (Map<String, Object> excluded : excludedDatasets) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes, AGENT_RUN_ID_ATTRIBUTE,
                "数据集未进入分析：" + excluded.get("datasetReference") + "（未返回非空结构化记录）。",
                "analysis_summary_governance",
                metadataOf("type", "analysis_dataset_excluded", "exclusion", excluded,
                    "tenantId", isolationScope.tenantId(), "runId", isolationScope.runId()));
        }
        if (recordSets.isEmpty()) {
            return RecordCoverageBundle.empty();
        }
        DatasetRelationshipPlan relationshipPlan = buildDatasetRelationshipPlan(recordSets);
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            "已完成数据集关系分析，共形成 " + relationshipPlan.groups().size() + " 个分析组。",
            "analysis_summary_governance",
            metadataOf(
                "type", "dataset_relationship_plan",
                "relationshipPlan", relationshipPlan.toMap(),
                "tenantId", isolationScope.tenantId(),
                "runId", isolationScope.runId()
            )
        );
        ParallelAnalysisSummaryBatch parallelSummaries = prepareParallelAnalysisSummaries(
            activeChatModel, query, recordSets, isolationScope, runtimeAttributes,
            cancellationCheck);
        if (metadata != null) {
            metadata.put("recordAnalysisSummaryParallel", parallelSummaries.isParallel());
            metadata.put("recordAnalysisSummaryScheduledTaskCount", parallelSummaries.taskCount());
            metadata.put("recordAnalysisSummaryWorkerCount", parallelSummaries.workerCount());
            metadata.put("recordAnalysisSummaryDispatchMode", parallelSummaries.mode());
            metadata.put("recordAnalysisSummaryWorkerHeartbeatIntervalMs",
                analysisSummaryWorkerHeartbeatIntervalMs);
            metadata.put("recordAnalysisSummaryWorkerHeartbeatTimeoutMs",
                analysisSummaryWorkerHeartbeatTimeoutMs);
            metadata.put("recordAnalysisWorkerModelTimeoutPolicy",
                "SYSTEM_MODEL_REQUEST_TIMEOUT");
            metadata.put("recordAnalysisSummaryWorkerMaxRetries",
                analysisSummaryWorkerMaxRetries);
            metadata.put("recordAnalysisSummaryWorkerMaxAttempts",
                analysisSummaryWorkerMaxRetries + 1);
        }
        if (parallelSummaries.taskCount() > 0) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                AGENT_RUN_ID_ATTRIBUTE,
                "\u5df2\u5c06 " + parallelSummaries.taskCount() + " \u4e2a\u6570\u636e\u96c6\u5206\u6790\u4efb\u52a1\u5206\u53d1\u7ed9 "
                    + parallelSummaries.workerCount() + " \u4e2a worker\u3002",
                "analysis_summary_governance",
                metadataOf(
                    "type", "analysis_summary_dispatched",
                    "taskCount", parallelSummaries.taskCount(),
                    "workerCount", parallelSummaries.workerCount(),
                    "dispatchMode", parallelSummaries.mode(),
                    "tenantId", isolationScope.tenantId(),
                    "runId", isolationScope.runId()
                )
            );
        }
        try {
        StringBuilder promptEvidence = new StringBuilder(
            "Returned-record evidence (record_grounded_analysis.v1). "
                + "Every successful range below is processed evidence; final analysis must use it, "
                + "must not substitute execution metadata, and must respect listed Worker failures.\n");
        StringBuilder appendix = new StringBuilder();
        StringBuilder rawReplayEvidence = new StringBuilder();
        List<List<String>> recordValueGroups = new ArrayList<>();
        int returnedRecordCount = 0;
        int processedRecordCount = 0;
        int iterations = 0;
        boolean iterative = false;
        boolean sourceContentComplete = true;
        int rawReplayChunkCount = 0;
        int spilledChunkCount = 0;
        int restoredCheckpointCount = 0;
        int restoredDatasetReductionCount = 0;
        int retriedChunkCount = 0;
        int analysisRetryCount = 0;
        long spilledByteCount = 0;
        Map<String, Integer> datasetOccurrences = new LinkedHashMap<>();
        List<AnalysisSummaryResult> governedSummaryResults = new ArrayList<>();
        List<AnalysisSummaryResult> workerDatasetSummaryResults = new ArrayList<>();
        List<Map<String, Object>> deterministicInsightResults = new ArrayList<>();
        List<DeterministicInsightEngine.DatasetInput> deterministicInsightDatasets = new ArrayList<>();
        List<Map<String, Object>> deterministicInsightDecisions = new ArrayList<>();
        List<Map<String, Object>> semanticPresentationViews = new ArrayList<>();
        List<Map<String, Object>> failedAnalysisDatasets = new ArrayList<>();
        int analyzedDatasetCount = 0;
        int datasetIndex = 0;
        for (BatchRecordSet recordSet : recordSets) {
            datasetIndex++;
            int datasetOccurrence = datasetOccurrences.merge(recordSet.reference(), 1, Integer::sum);
            String evidenceReference = datasetOccurrence == 1
                ? recordSet.reference()
                : recordSet.reference() + "#occurrence-" + datasetOccurrence;
            returnedRecordCount += recordSet.records().size();
            sourceContentComplete &= recordSet.records().stream()
                .noneMatch(record -> Boolean.FALSE.equals(record.get("sourceComplete")));
            AnalysisDatasetTaskOutcome workerOutcome =
                parallelSummaries.await(evidenceReference);
            AnalysisDatasetSummary datasetSummary = workerOutcome.summary();
            if (!workerOutcome.success()) {
                Map<String, Object> failedDataset = metadataOf(
                    "datasetReference", evidenceReference,
                    "datasetIndex", datasetIndex,
                    "datasetCount", recordSets.size(),
                    "recordCount", recordSet.records().size(),
                    "status", workerOutcome.status(),
                    "workerId", workerOutcome.workerId(),
                    "durationMs", workerOutcome.durationMs(),
                    "error", workerOutcome.error()
                );
                failedAnalysisDatasets.add(failedDataset);
                promptEvidence.append("- ").append(evidenceReference)
                    .append(" was not analyzed because its Worker failed. Do not infer facts from this dataset. Failure: ")
                    .append(ModelProtocolJson.compact(failedDataset)).append("\n");
                appendix.append("### ").append(evidenceReference).append("\n\n")
                    .append("- 分析未完成：Worker ")
                    .append(workerOutcome.status()).append("，")
                    .append(firstNonBlank(workerOutcome.error(), "未返回可用结果"))
                    .append("。\n\n");
                runResultAdapter.recordRuntimeObservation(
                    runtimeAttributes,
                    AGENT_RUN_ID_ATTRIBUTE,
                    "数据集 " + datasetIndex + "/" + recordSets.size()
                        + " 的 Worker 分析失败，Driver 将继续收集其他数据集结果。",
                    "analysis_worker_progress",
                    metadataOf(
                        "type", "analysis_driver_dataset_failure_isolated",
                        "stage", "DRIVER_DATASET_FAILURE_ISOLATED",
                        "failure", failedDataset,
                        "tenantId", isolationScope.tenantId(),
                        "runId", isolationScope.runId()
                    )
                );
                continue;
            }
            analyzedDatasetCount++;
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                AGENT_RUN_ID_ATTRIBUTE,
                "Driver 已收到数据集 " + evidenceReference + " 的 Worker 分析结果。",
                "analysis_worker_progress",
                metadataOf(
                    "type", "analysis_driver_result_collected",
                    "stage", "DRIVER_RESULT_COLLECTED",
                    "datasetReference", evidenceReference,
                    "datasetIndex", datasetIndex,
                    "datasetCount", recordSets.size(),
                    "chunkCount", datasetSummary.chunks().size(),
                    "tenantId", isolationScope.tenantId(),
                    "runId", isolationScope.runId()
                )
            );
            boolean oversized = datasetSummary.oversized();
            isolationScope.requireSamePartition(datasetSummary.datasetSummary().isolationScope());
            workerDatasetSummaryResults.add(datasetSummary.datasetSummary());
            if (!oversized) {
                recordSet.records().forEach(record ->
                    recordValueGroups.add(recordValueGroup(record, query)));
            }
            iterative |= oversized;
            Map<String, Object> governedContext = analysisSummaryGovernanceBridge.govern(
                evidenceReference, recordSet.analysisContext(), recordSet.records());
            Map<String, Object> semanticPresentationView =
                AnalysisContextPresentationContract.semanticView(evidenceReference, governedContext);
            semanticPresentationViews.add(semanticPresentationView);
            promptEvidence.append("- ").append(evidenceReference)
                .append(" business semantic view: ")
                .append(ModelProtocolJson.compact(semanticPresentationView))
                .append("\n");
            SemanticInsightContractProvider.Resolution insightResolution =
                resolveSemanticInsightContracts(isolationScope, evidenceReference,
                    governedContext, runtimeAttributes, metadata);
            deterministicInsightDecisions.add(metadataOf(
                "dataset", evidenceReference,
                "status", insightResolution.status(),
                "reason", insightResolution.reason(),
                "contractIds", insightResolution.contracts().stream()
                    .map(SemanticInsightContract::contractId).toList()
            ));
            for (SemanticInsightContract contract : insightResolution.contracts()) {
                DeterministicInsightEngine.Result insightResult = deterministicInsightEngine.analyze(
                    isolationScope, evidenceReference, contract, recordSet.records());
                deterministicInsightDatasets.add(new DeterministicInsightEngine.DatasetInput(
                    evidenceReference, contract, recordSet.records()));
                if (!insightResult.executed()) continue;
                deterministicInsightResults.add(insightResult.toMap());
                promptEvidence.append("- ").append(evidenceReference)
                    .append(" deterministic findings (authoritative calculations; the model may explain but must not recalculate or alter them): ")
                    .append(ModelProtocolJson.compact(insightResult.toMap())).append("\n");
                runResultAdapter.recordRuntimeObservation(runtimeAttributes, AGENT_RUN_ID_ATTRIBUTE,
                    "Deterministic semantic insights recorded for " + evidenceReference + ".",
                    "deterministic_insights", metadataOf("type", "deterministic_insights",
                        "result", insightResult.toMap(), "tenantId", isolationScope.tenantId(),
                        "runId", isolationScope.runId()));
            }
            appendix.append("### ").append(evidenceReference).append("\n\n");
            spilledChunkCount += datasetSummary.spilledChunkCount();
            spilledByteCount += datasetSummary.spilledByteCount();
            restoredCheckpointCount += datasetSummary.restoredCheckpointCount();
            if (datasetSummary.datasetReductionRestoredCheckpoint()) {
                restoredDatasetReductionCount++;
            }
            retriedChunkCount += datasetSummary.retriedChunkCount();
            analysisRetryCount += datasetSummary.totalRetryCount();
            iterations += datasetSummary.chunks().size();
            for (AnalysisDatasetSummary.ChunkResult chunkResult : datasetSummary.chunks()) {
                runtimeGuard.checkCancelled(cancellationCheck);
                AnalysisSummaryResult governedSummary = chunkResult.summary();
                Map<String, Object> positionMap = governedSummary.position();
                int from = intValue(positionMap.get("recordFrom"), 1);
                int to = intValue(positionMap.get("recordTo"), from);
                int chunkIndex = intValue(positionMap.get("chunkIndex"), 1);
                int chunkCount = intValue(positionMap.get("chunkCount"),
                    datasetSummary.chunks().size());
                if (from < 1 || to < from || to > recordSet.records().size()) {
                    throw new IllegalStateException(
                        "Worker returned invalid record range for " + evidenceReference);
                }
                List<Map<String, Object>> chunk = recordSet.records().subList(from - 1, to);
                String rawChunkJson = ModelProtocolJson.compact(chunk);
                AnalysisEvidenceSpillStore.SpillReference spillReference =
                    chunkResult.spillReference();
                governedSummaryResults.add(governedSummary);
                String analysis = governedSummary.content();
                if (metadata != null
                    && "STRUCTURED_RECORD_FALLBACK".equals(governedSummary.outcome())) {
                    metadata.put("recordAnalysisChunkFallback", true);
                }
                processedRecordCount += chunk.size();
                String range = "records[" + from + ".." + to + "]";
                appendix.append("- ").append(range).append("：")
                    .append(analysis).append("\n");
                if (requiresRawEvidenceReplay(governedSummary)) {
                    rawReplayChunkCount++;
                    String replayJson = spillReference == null
                        ? rawChunkJson
                        : new String(analysisEvidenceSpillStore.read(isolationScope, spillReference),
                            StandardCharsets.UTF_8);
                    rawReplayEvidence.append("- evidenceId=")
                        .append(stringValue(governedSummary.evidence().get("evidenceId")))
                        .append(" position=")
                        .append(ModelProtocolJson.compact(positionMap))
                        .append(" contentSha256=")
                        .append(stringValue(governedSummary.evidence().get("contentSha256")))
                        .append(" rawRecords=")
                        .append(replayJson)
                        .append("\n");
                }
            }
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                AGENT_RUN_ID_ATTRIBUTE,
                "数据集 " + datasetIndex + "/" + recordSets.size() + " 分析完成（"
                    + datasetSummary.chunks().size() + " 个数据切片，由 worker 处理）。",
                "analysis_summary_governance",
                metadataOf(
                    "type", "analysis_dataset_completed",
                    "datasetReference", evidenceReference,
                    "datasetIndex", datasetIndex,
                    "datasetCount", recordSets.size(),
                    "chunkCount", datasetSummary.chunks().size(),
                    "workerOwnedChunking", true,
                    "spilledChunkCount", datasetSummary.spilledChunkCount(),
                    "restoredCheckpointCount", datasetSummary.restoredCheckpointCount(),
                    "retriedChunkCount", datasetSummary.retriedChunkCount(),
                    "retryCount", datasetSummary.totalRetryCount(),
                    "datasetReductionAttemptCount",
                        datasetSummary.datasetReductionAttemptCount(),
                    "datasetReductionRestoredCheckpoint",
                        datasetSummary.datasetReductionRestoredCheckpoint(),
                    "datasetSummaryResultId", datasetSummary.datasetSummary().resultId(),
                    "summaryResultIds", datasetSummary.inputSummaryResultIds(),
                    "tenantId", isolationScope.tenantId(),
                    "runId", isolationScope.runId()
                )
            );
            appendix.append("\n");
        }
        DeterministicInsightEngine.Result bundleInsightResult = deterministicInsightEngine.analyzeBundle(
            isolationScope, deterministicInsightDatasets);
        if (bundleInsightResult.executed()
            && (!bundleInsightResult.findings().isEmpty() || !bundleInsightResult.issues().isEmpty())) {
            deterministicInsightResults.add(bundleInsightResult.toMap());
            promptEvidence.append("Cross-dataset deterministic findings (authoritative calculations): ")
                .append(ModelProtocolJson.compact(bundleInsightResult.toMap())).append("\n");
        }
        boolean coverageComplete = processedRecordCount == returnedRecordCount;
        boolean evidenceTraceComplete = processedRecordCount > 0
            && governedSummaryResults.size() == iterations
            && governedSummaryResults.stream().allMatch(this::hasTraceableChunkEvidence)
            && governedSummaryResults.stream().map(AnalysisSummaryResult::resultId).distinct().count()
                == governedSummaryResults.size();
        promptEvidence.append("Coverage: returnedRecordCount=").append(returnedRecordCount)
            .append(", processedRecordCount=").append(processedRecordCount)
            .append(", complete=").append(coverageComplete)
            .append(", sourceContentComplete=").append(sourceContentComplete)
            .append(", evidenceTraceComplete=").append(evidenceTraceComplete)
            .append(", rawReplayChunkCount=").append(rawReplayChunkCount).append(".\n");
        if (!failedAnalysisDatasets.isEmpty()) {
            promptEvidence.append("Worker failure isolation: analyzedDatasetCount=")
                .append(analyzedDatasetCount)
                .append(", failedDatasetCount=").append(failedAnalysisDatasets.size())
                .append(". Final conclusions must be limited to successful datasets. Failed datasets: ")
                .append(ModelProtocolJson.compact(failedAnalysisDatasets)).append("\n");
        }
        HierarchicalAnalysisReducer.Result hierarchicalResult = hierarchicalAnalysisReducer.reduce(
            new HierarchicalAnalysisReducer.Context(
                activeChatModel::chat, isolationScope, relationshipPlan, query),
            workerDatasetSummaryResults);
        promptEvidence.append(hierarchicalResult.promptEvidence());
        if (!rawReplayEvidence.isEmpty()) {
            promptEvidence.append("Raw evidence replay (lossless, selected by generic integrity rules). "
                    + "Use only when an intermediate summary is incomplete or inconsistent:\n")
                .append(rawReplayEvidence);
        }
        if (!hierarchicalResult.uncoveredDatasets().isEmpty()) {
            promptEvidence.append("Runtime relationship coverage recovery retained uncovered datasets "
                    + "as standalone final inputs: ")
                .append(ModelProtocolJson.compact(hierarchicalResult.uncoveredDatasets())).append("\n");
        }
        for (AnalysisSummaryResult datasetSummary : hierarchicalResult.datasetSummaries()) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes, AGENT_RUN_ID_ATTRIBUTE,
                "数据集归并分析完成：" + datasetSummary.position().get("datasetReference") + "。",
                "analysis_summary_governance",
                metadataOf("type", "dataset_synthesis", "analysisSummaryResult", datasetSummary.toMap(),
                    "tenantId", isolationScope.tenantId(), "runId", isolationScope.runId()));
        }
        for (AnalysisSummaryResult groupSummary : hierarchicalResult.relationshipGroupSummaries()) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes, AGENT_RUN_ID_ATTRIBUTE,
                "关系组归并分析完成：" + groupSummary.position().get("groupId") + "。",
                "analysis_summary_governance",
                metadataOf("type", "relationship_group_synthesis",
                    "analysisSummaryResult", groupSummary.toMap(),
                    "tenantId", isolationScope.tenantId(), "runId", isolationScope.runId()));
        }
        if (metadata != null) {
            metadata.put("recordAnalysisContractVersion", "record_grounded_analysis.v1");
            metadata.put("recordAnalysisReturnedRecordCount", returnedRecordCount);
            metadata.put("recordAnalysisProcessedRecordCount", processedRecordCount);
            metadata.put("recordAnalysisCoverageComplete", coverageComplete);
            metadata.put("recordAnalysisDatasetCount", recordSets.size());
            metadata.put("recordAnalysisSuccessfulDatasetCount", analyzedDatasetCount);
            metadata.put("recordAnalysisFailedDatasetCount", failedAnalysisDatasets.size());
            metadata.put("recordAnalysisFailedDatasets", List.copyOf(failedAnalysisDatasets));
            metadata.put("recordAnalysisPartialWorkerFailure",
                analyzedDatasetCount > 0 && !failedAnalysisDatasets.isEmpty());
            metadata.put("recordAnalysisAllWorkersFailed",
                analyzedDatasetCount == 0 && !failedAnalysisDatasets.isEmpty());
            metadata.put("recordAnalysisEvidenceTraceComplete", evidenceTraceComplete);
            metadata.put("recordAnalysisSourceContentComplete", sourceContentComplete);
            metadata.put("recordAnalysisIterationCount", iterations);
            metadata.put("recordAnalysisIterative", iterative);
            metadata.put("recordAnalysisRawReplayChunkCount", rawReplayChunkCount);
            metadata.put("recordAnalysisSpilledChunkCount", spilledChunkCount);
            metadata.put("recordAnalysisSpilledByteCount", spilledByteCount);
            metadata.put("recordAnalysisRestoredCheckpointCount", restoredCheckpointCount);
            metadata.put("recordAnalysisRestoredDatasetReductionCount",
                restoredDatasetReductionCount);
            metadata.put("recordAnalysisRetriedChunkCount", retriedChunkCount);
            metadata.put("recordAnalysisRetryCount", analysisRetryCount);
            metadata.put("analysisSummaryGovernanceBridge",
                analysisSummaryGovernanceBridge.ledger(governedSummaryResults,
                    returnedRecordCount, processedRecordCount, coverageComplete));
            metadata.put("datasetRelationshipPlan", relationshipPlan.toMap());
            metadata.put("datasetRelationshipGroupCount", relationshipPlan.groups().size());
            metadata.put("datasetRelationshipEdgeCount", relationshipPlan.edges().size());
            metadata.put("datasetRelationshipUnresolvedReferences",
                relationshipPlan.unresolvedReferences());
            metadata.put("hierarchicalDatasetSummaryCount",
                hierarchicalResult.datasetSummaries().size());
            metadata.put("hierarchicalRelationshipGroupSummaryCount",
                hierarchicalResult.relationshipGroupSummaries().size());
            metadata.put("hierarchicalFinalInputCount", hierarchicalResult.finalInputs().size());
            metadata.put("hierarchicalUncoveredDatasets", hierarchicalResult.uncoveredDatasets());
            metadata.put("hierarchicalAnalysisReduce", metadataOf(
                "schemaVersion", HierarchicalAnalysisReducer.SCHEMA_VERSION,
                "relationshipPlan", relationshipPlan.toMap(),
                "datasetSummaries", hierarchicalResult.datasetSummaries().stream()
                    .map(AnalysisSummaryResult::toMap).toList(),
                "relationshipGroupSummaries", hierarchicalResult.relationshipGroupSummaries().stream()
                    .map(AnalysisSummaryResult::toMap).toList(),
                "finalInputSummaryResultIds", hierarchicalResult.finalInputs().stream()
                    .map(AnalysisSummaryResult::resultId).toList(),
                "uncoveredDatasets", hierarchicalResult.uncoveredDatasets()
            ));
            metadata.put("deterministicInsightContractVersion", DeterministicInsightEngine.RESULT_VERSION);
            metadata.put("deterministicInsightResults", List.copyOf(deterministicInsightResults));
            metadata.put("deterministicInsightApplicability", List.copyOf(deterministicInsightDecisions));
            metadata.put("analysisContextPresentationVersion", AnalysisContextPresentationContract.VERSION);
            metadata.put("analysisContextPresentationViews", List.copyOf(semanticPresentationViews));
            metadata.put("deterministicInsightFindingCount", deterministicInsightResults.stream()
                .mapToInt(item -> {
                    Object findings = item.get("findings");
                    return findings instanceof List<?> list ? list.size() : 0;
                }).sum());
        }
        return new RecordCoverageBundle(
            promptEvidence.toString(), appendix.toString(), List.copyOf(recordValueGroups),
            returnedRecordCount, processedRecordCount, iterations, iterative, coverageComplete,
            sourceContentComplete, evidenceTraceComplete, rawReplayChunkCount,
            List.copyOf(governedSummaryResults), hierarchicalResult.finalInputs());
        } finally {
            parallelSummaries.close();
        }
    }

    private DatasetRelationshipPlan buildDatasetRelationshipPlan(List<BatchRecordSet> recordSets) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<DatasetRelationshipPlan.Dataset> datasets = new ArrayList<>();
        for (BatchRecordSet recordSet : recordSets) {
            int occurrence = occurrences.merge(recordSet.reference(), 1, Integer::sum);
            String reference = occurrence == 1
                ? recordSet.reference() : recordSet.reference() + "#occurrence-" + occurrence;
            datasets.add(new DatasetRelationshipPlan.Dataset(reference,
                analysisSummaryGovernanceBridge.govern(
                    reference, recordSet.analysisContext(), recordSet.records())));
        }
        return DatasetRelationshipPlan.create(datasets);
    }

    private ParallelAnalysisSummaryBatch prepareParallelAnalysisSummaries(
        ChatModel activeChatModel,
        String query,
        List<BatchRecordSet> recordSets,
        GovernanceIsolationScope isolationScope,
        Map<String, Object> runtimeAttributes,
        BooleanSupplier cancellationCheck
    ) {
        List<AnalysisTask> tasks = new ArrayList<>();
        Map<String, String> taskIdsByDatasetReference = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        int datasetIndex = 0;
        for (BatchRecordSet recordSet : recordSets) {
            datasetIndex++;
            int occurrence = occurrences.merge(recordSet.reference(), 1, Integer::sum);
            String evidenceReference = occurrence == 1
                ? recordSet.reference()
                : recordSet.reference() + "#occurrence-" + occurrence;
            Map<String, Object> governedContext = analysisSummaryGovernanceBridge.govern(
                evidenceReference, recordSet.analysisContext(), recordSet.records());
            String inputSha256 = ModelProtocolJson.sha256Hex(Map.of(
                "schemaVersion", AnalysisTask.SCHEMA_VERSION,
                "datasetReference", evidenceReference,
                "records", recordSet.records(),
                "analysisContext", governedContext,
                "userObjective", firstNonBlank(query, ""),
                "maximumChunkRows", recordAnalysisChunkMaxRows,
                "maximumChunkChars", recordAnalysisChunkMaxChars,
                "maximumRetries", analysisSummaryWorkerMaxRetries
            ));
            String taskId = isolationScope.partitionKey() + ":" + evidenceReference;
            Map<String, Object> evidenceLocator = metadataOf(
                "datasetReference", evidenceReference,
                "canonicalPath", governedContext.get("canonicalPath"),
                "sourcePayloadPreservation",
                    governedContext.getOrDefault("sourcePayloadPreservation", Map.of())
            );
            AnalysisTask task = new AnalysisTask(
                AnalysisTask.SCHEMA_VERSION, taskId, inputSha256, isolationScope,
                evidenceReference, datasetIndex, recordSets.size(), governedContext,
                evidenceLocator,
                recordSet.records(), query, recordAnalysisChunkMaxRows,
                recordAnalysisChunkMaxChars, analysisSpillThresholdBytes,
                analysisSummaryWorkerMaxRetries,
                analysisSummaryWorkerHeartbeatTimeoutMs, 1);
            tasks.add(task);
            taskIdsByDatasetReference.put(evidenceReference, taskId);
        }
        if (tasks.isEmpty()) {
            return ParallelAnalysisSummaryBatch.disabled();
        }
        AnalysisTaskDispatcher.DispatchBatch dispatched = analysisTaskDispatcher.dispatch(
            tasks,
            (task, progressReporter) -> {
                runtimeGuard.checkCancelled(cancellationCheck);
                return analyzeDatasetTask(activeChatModel, task, progressReporter,
                    cancellationCheck);
            },
            cancellationCheck,
            progress -> recordAnalysisWorkerProgress(
                runtimeAttributes, isolationScope, progress));
        log.info("analysisTaskDriverDispatched mode={} taskCount={} workerCount={}",
            dispatched.mode(), dispatched.taskCount(), dispatched.workerCount());
        return new ParallelAnalysisSummaryBatch(
            dispatched, tasks, taskIdsByDatasetReference);
    }

    private AnalysisDatasetSummary analyzeDatasetTask(
        ChatModel activeChatModel,
        AnalysisTask task,
        AnalysisTaskProgressReporter progressReporter,
        BooleanSupplier cancellationCheck
    ) {
        RecordChunkPlan chunkPlan = recordChunkPlan(
            task.records(), task.maximumChunkRows(), task.maximumChunkChars());
        boolean modelSummaryRequired = analysisSummaryGovernanceBridge.requiresModelSummary(
            task.analysisContext(), chunkPlan.oversized());
        List<AnalysisDatasetSummary.ChunkResult> chunks = new ArrayList<>();
        int spilledChunkCount = 0;
        long spilledByteCount = 0;
        int restoredCheckpointCount = 0;
        int from = 1;
        for (int chunkOffset = 0; chunkOffset < chunkPlan.ranges().size(); chunkOffset++) {
            runtimeGuard.checkCancelled(cancellationCheck);
            RecordRange range = chunkPlan.ranges().get(chunkOffset);
            List<Map<String, Object>> chunk = List.copyOf(task.records()
                .subList(range.fromInclusive(), range.toExclusive()));
            int to = from + chunk.size() - 1;
            RuntimeAnalysisPosition position = analysisSummaryGovernanceBridge.position(
                task.datasetReference(), chunkOffset + 1, chunkPlan.ranges().size(),
                from, to, task.records().size());
            progressReporter.report("CHUNK_STARTED", metadataOf(
                "chunkIndex", position.chunkIndex(),
                "chunkCount", position.chunkCount(),
                "recordFrom", from,
                "recordTo", to,
                "recordCount", chunk.size()
            ));
            String rawJson = ModelProtocolJson.compact(chunk);
            byte[] rawBytes = rawJson.getBytes(StandardCharsets.UTF_8);
            String contentSha256 = ModelProtocolJson.sha256Hex(rawJson);
            String evidenceId = task.isolationScope().partitionKey() + ":"
                + task.datasetReference() + "#chunk-" + (chunkOffset + 1);
            boolean spillRequested = analysisEvidenceSpillStore.isEnabled()
                && (chunkPlan.oversized() || rawBytes.length >= task.spillThresholdBytes());
            AnalysisEvidenceSpillStore.SpillReference spillReference = null;
            if (spillRequested) {
                spillReference = analysisEvidenceSpillStore.spill(
                    task.isolationScope(), evidenceId, contentSha256, rawBytes);
                spilledChunkCount++;
                spilledByteCount += spillReference.byteLength();
            }
            String checkpointInputSha256 = summaryCheckpointInputSha256(
                contentSha256, position, task.analysisContext(), task.originalUserQuestion(),
                modelSummaryRequired);
            String checkpointKey = task.datasetReference() + "#chunk-" + (chunkOffset + 1);
            AnalysisSummaryResult summary = null;
            boolean restoredCheckpoint = false;
            AtomicInteger attemptCount = new AtomicInteger();
            if (spillReference != null) {
                summary = restoreAnalysisSummaryCheckpoint(
                    task.isolationScope(), checkpointKey, checkpointInputSha256);
                restoredCheckpoint = summary != null;
                if (restoredCheckpoint) restoredCheckpointCount++;
            }
            if (summary == null) {
                summary = modelSummaryRequired
                    ? analysisSummaryGovernanceBridge.summarize(
                        prompt -> analysisWorkerRetryPolicy.execute(
                            task.maximumRetries(), cancellationCheck,
                            ignored -> {
                                String output = activeChatModel.chat(prompt);
                                if (output == null || output.isBlank()) {
                                    throw new IllegalStateException(
                                        "Analysis model returned an empty response");
                                }
                                return output;
                            },
                            attemptCount::set,
                            (attempt, failure) -> {
                                log.warn(
                                    "analysisChunkAttemptFailed dataset={} chunk={}/{} attempt={}/{} error={}",
                                    task.datasetReference(), position.chunkIndex(),
                                    position.chunkCount(), attempt, task.maximumAttempts(),
                                    failure.getMessage());
                                progressReporter.report("CHUNK_RETRY", metadataOf(
                                    "chunkIndex", position.chunkIndex(),
                                    "chunkCount", position.chunkCount(),
                                    "failedAttempt", attempt,
                                    "maximumAttempts", task.maximumAttempts(),
                                    "error", String.valueOf(failure.getMessage())
                                ));
                            }),
                        task.isolationScope(), position,
                        task.analysisContext(), chunk, task.originalUserQuestion())
                    : analysisSummaryGovernanceBridge.preserve(
                        task.isolationScope(), position, task.analysisContext(), chunk);
                if (spillReference != null
                    && !"STRUCTURED_RECORD_FALLBACK".equals(summary.outcome())) {
                    persistAnalysisSummaryCheckpoint(
                        task.isolationScope(), checkpointKey, checkpointInputSha256, summary);
                }
            }
            if (spillReference != null) {
                summary = summary.withEvidence(Map.of(
                    "rawReplayLocator", spillReference.toMap(),
                    "spillCheckpointKey", checkpointKey,
                    "spillCheckpointInputSha256", checkpointInputSha256
                ));
            }
            summary = summary.withEvidence(Map.of(
                "workerAttemptCount", attemptCount.get(),
                "workerRetryCount", Math.max(0, attemptCount.get() - 1),
                "workerMaximumRetries", task.maximumRetries(),
                "workerMaximumAttempts", task.maximumAttempts()
            ));
            chunks.add(new AnalysisDatasetSummary.ChunkResult(
                summary, spillReference, checkpointInputSha256, restoredCheckpoint,
                attemptCount.get()));
            progressReporter.report("CHUNK_COMPLETED", metadataOf(
                "chunkIndex", position.chunkIndex(),
                "chunkCount", position.chunkCount(),
                "recordFrom", from,
                "recordTo", to,
                "recordCount", chunk.size(),
                "attemptCount", attemptCount.get(),
                "restoredCheckpoint", restoredCheckpoint,
                "outcome", summary.outcome()
            ));
            from = to + 1;
        }
        AtomicInteger datasetReductionAttemptCount = new AtomicInteger();
        List<AnalysisSummaryResult> chunkSummaries = chunks.stream()
            .map(AnalysisDatasetSummary.ChunkResult::summary).toList();
        String datasetReductionCheckpointKey = task.datasetReference() + "#dataset-reduce";
        String datasetReductionInputSha256 = ModelProtocolJson.sha256Hex(Map.of(
            "schemaVersion", HierarchicalAnalysisReducer.SCHEMA_VERSION,
            "datasetReference", task.datasetReference(),
            "analysisContext", task.analysisContext(),
            "originalUserQuestion", task.originalUserQuestion(),
            "chunkSummaries", chunkSummaries.stream()
                .map(this::datasetReductionCheckpointProjection).toList()
        ));
        boolean datasetReductionCheckpointEligible = analysisEvidenceSpillStore.isEnabled()
            && chunkPlan.oversized();
        AnalysisSummaryResult datasetSummary = datasetReductionCheckpointEligible
            ? restoreAnalysisSummaryCheckpoint(task.isolationScope(),
                datasetReductionCheckpointKey, datasetReductionInputSha256)
            : null;
        boolean datasetReductionRestoredCheckpoint = datasetSummary != null;
        if (datasetSummary == null) {
            progressReporter.report("DATASET_REDUCING", metadataOf(
                "chunkCount", chunkSummaries.size(),
                "originalQuestionPresent", !task.originalUserQuestion().isBlank()
            ));
            datasetSummary = workerDatasetReducer.reduceDataset(
                prompt -> analysisWorkerRetryPolicy.execute(
                    task.maximumRetries(), cancellationCheck,
                    ignored -> {
                        String output = activeChatModel.chat(prompt);
                        if (output == null || output.isBlank()) {
                            throw new IllegalStateException(
                                "Dataset reduction model returned an empty response");
                        }
                        return output;
                    },
                    datasetReductionAttemptCount::set,
                    (attempt, failure) -> {
                        log.warn(
                            "analysisDatasetReduceAttemptFailed dataset={} attempt={}/{} error={}",
                            task.datasetReference(), attempt, task.maximumAttempts(),
                            failure.getMessage());
                        progressReporter.report("DATASET_REDUCTION_RETRY", metadataOf(
                            "failedAttempt", attempt,
                            "maximumAttempts", task.maximumAttempts(),
                            "error", String.valueOf(failure.getMessage())
                        ));
                    }),
                task.isolationScope(), task.datasetReference(), chunkSummaries,
                task.originalUserQuestion());
            if (datasetReductionCheckpointEligible
                && !datasetSummary.outcome().contains("FALLBACK")) {
                persistAnalysisSummaryCheckpoint(task.isolationScope(),
                    datasetReductionCheckpointKey, datasetReductionInputSha256, datasetSummary);
            }
        }
        return AnalysisDatasetSummary.completed(task, chunkPlan.oversized(),
            chunkPlan.serializedChars(), chunks, datasetSummary, spilledChunkCount,
            spilledByteCount, restoredCheckpointCount, datasetReductionAttemptCount.get(),
            datasetReductionRestoredCheckpoint);
    }

    private void recordAnalysisWorkerProgress(
        Map<String, Object> runtimeAttributes,
        GovernanceIsolationScope isolationScope,
        AnalysisTaskProgress progress
    ) {
        if (progress == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(progress.toMap());
        metadata.put("type", "analysis_worker_" + progress.stage().toLowerCase(Locale.ROOT));
        metadata.put("tenantId", isolationScope.tenantId());
        metadata.put("runId", isolationScope.runId());
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            analysisWorkerProgressContent(progress),
            "analysis_worker_progress",
            metadata
        );
    }

    private String analysisWorkerProgressContent(AnalysisTaskProgress progress) {
        String position = progress.datasetIndex() + "/" + progress.datasetCount();
        String dataset = progress.datasetReference();
        Object chunkIndex = progress.details().get("chunkIndex");
        Object chunkCount = progress.details().get("chunkCount");
        return switch (progress.stage()) {
            case "WORKER_CLAIMED" -> progress.workerId() + " 已领取数据集 "
                + position + "：" + dataset + "。";
            case "WORKER_HEARTBEAT" -> progress.workerId() + " 正在分析数据集 "
                + position + "，Worker 心跳正常。";
            case "CHUNK_STARTED" -> progress.workerId() + " 正在分析数据集 "
                + position + " 的分片 " + chunkIndex + "/" + chunkCount + "。";
            case "CHUNK_COMPLETED" -> progress.workerId() + " 已完成数据集 "
                + position + " 的分片 " + chunkIndex + "/" + chunkCount + "。";
            case "CHUNK_RETRY" -> progress.workerId() + " 正在重试数据集 "
                + position + " 的分片 " + chunkIndex + "/" + chunkCount + "。";
            case "DATASET_REDUCING" -> progress.workerId() + " 正在归并数据集 "
                + position + " 的分片结果。";
            case "DATASET_REDUCTION_RETRY" -> progress.workerId()
                + " 正在重试数据集 " + position + " 的归并分析。";
            case "DATASET_COMPLETED" -> progress.workerId() + " 已完成数据集 "
                + position + "：" + dataset + "。";
            case "DATASET_FAILED" -> progress.workerId() + " 分析数据集 "
                + position + " 失败：" + progress.details().get("error") + "。";
            default -> progress.workerId() + " 更新了数据集 " + position + " 的分析进度。";
        };
    }

    /**
     * Builds a stable dataset-reduction identity. Worker attempt counters and spill locators are
     * deliberately excluded: they describe how a chunk was computed, not what the chunk means.
     * This lets a retried Driver reuse the completed Worker reduction after restoring chunk
     * checkpoints without changing the semantic input hash.
     */
    private Map<String, Object> datasetReductionCheckpointProjection(
        AnalysisSummaryResult summary
    ) {
        return Map.of(
            "resultId", summary.resultId(),
            "content", summary.content(),
            "outcome", summary.outcome(),
            "position", summary.position(),
            "analysisContext", summary.analysisContext(),
            "coverage", summary.coverage()
        );
    }

    private SemanticInsightContractProvider.Resolution resolveSemanticInsightContracts(
        GovernanceIsolationScope scope, String datasetReference, Map<String, Object> governedContext,
        Map<String, Object> runtimeAttributes, Map<String, Object> metadata
    ) {
        Map<String, Object> attributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
        List<String> requestedIds = stringList(firstObject(attributes,
            "semanticInsightContractIds", "semantic_insight_contract_ids"));
        boolean explicitlyRequested = !requestedIds.isEmpty() || booleanValue(firstObject(attributes,
            "semanticInsightRequested", "semantic_insight_requested"));
        Map<String, Object> source = objectMap(governedContext == null ? null : governedContext.get("source"));
        String toolName = firstNonBlank(stringValue(source.get("remoteToolName")),
            firstNonBlank(stringValue(source.get("toolName")), stringValue(source.get("id"))));
        String agentId = firstNonBlank(stringValue(attributes.get("agentId")),
            stringValue(attributes.get("agent_id")));
        String taskType = firstNonBlank(metadata == null ? null : stringValue(metadata.get("taskType")),
            stringValue(attributes.get("taskType")));
        return semanticInsightContractProvider.resolve(new SemanticInsightContractProvider.Request(
            scope == null ? null : scope.tenantId(), agentId, taskType, toolName,
            datasetReference, explicitlyRequested, requestedIds));
    }

    private boolean hasTraceableChunkEvidence(AnalysisSummaryResult summary) {
        if (summary == null || summary.evidence() == null) return false;
        Map<String, Object> evidence = summary.evidence();
        return RuntimeAnalysisSummaryProtocol.EVIDENCE_SCHEMA_VERSION.equals(evidence.get("schemaVersion"))
            && !firstNonBlank(stringValue(evidence.get("evidenceId")), "").isBlank()
            && !firstNonBlank(stringValue(evidence.get("contentSha256")), "").isBlank()
            && Boolean.TRUE.equals(booleanValue(evidence.get("rawReplayAvailable")));
    }

    private boolean requiresRawEvidenceReplay(AnalysisSummaryResult summary) {
        if (!hasTraceableChunkEvidence(summary)) return true;
        Map<String, Object> evidence = summary.evidence();
        return !Boolean.TRUE.equals(booleanValue(evidence.get("structured")))
            || Boolean.TRUE.equals(booleanValue(evidence.get("rawReplayRecommended")))
            || Boolean.FALSE.equals(booleanValue(evidence.get("factRecordCoverageComplete")))
            || !Boolean.TRUE.equals(booleanValue(evidence.get("sourceComplete")))
            || collectionSize(evidence.get("conflicts")) > 0
            || intValue(evidence.get("rejectedFactCount"), 0) > 0;
    }

    private List<BatchRecordSet> executionRecordSets(InterpretationPlanRuntime.ExecutionResult result) {
        if (result == null || result.steps() == null) {
            return List.of();
        }
        List<BatchRecordSet> sets = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            if (step == null || !step.success()) {
                continue;
            }
            Object resolvedStepOutput = resolvedEvidenceData(step);
            if (resolvedStepOutput instanceof ToolCallBatchResult batch) {
                for (ToolCallResult child : batch.results()) {
                    if (!"SUCCESS".equalsIgnoreCase(child.status()) || !child.evidenceUsable()) {
                        continue;
                    }
                    String reference = firstNonBlank(child.templateId(),
                        firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "result")));
                    sets.addAll(outputRecordSets(child.output(), reference,
                        toolMetadataOrNull(child.toolName())));
                }
                continue;
            }
            // final_answer/reasoning nodes are model products, not executed evidence.
            // They intentionally have no tool name and must never enter record coverage.
            if (step.toolName() == null || step.toolName().isBlank()) continue;
            // Discovery outputs describe how to reach business data. Large template catalogs are
            // routing metadata, not datasets to summarize record by record.
            if (McpToolNamePolicy.isRoutingDiscovery(step.toolName())) continue;
            sets.addAll(outputRecordSets(
                resolvedStepOutput, step.toolName(), toolMetadataOrNull(step.toolName())));
        }
        return List.copyOf(sets);
    }

    private List<Map<String, Object>> excludedExecutionDatasets(
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        if (result == null || result.steps() == null) return List.of();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            if (step == null || !step.success()) continue;
            Object resolvedStepOutput = resolvedEvidenceData(step);
            if (!(resolvedStepOutput instanceof ToolCallBatchResult batch)) continue;
            for (ToolCallResult child : batch.results()) {
                if (!"SUCCESS".equalsIgnoreCase(child.status()) || !child.evidenceUsable()) continue;
                String reference = firstNonBlank(child.templateId(),
                    firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "result")));
                if (outputRecordSets(child.output(), reference,
                    toolMetadataOrNull(child.toolName())).isEmpty()) {
                    excluded.add(metadataOf(
                        "datasetReference", reference,
                        "toolName", child.toolName(),
                        "reason", "NO_NON_EMPTY_STRUCTURED_RECORDS",
                        "executionStatus", child.status()
                    ));
                }
            }
        }
        return List.copyOf(excluded);
    }

    private ToolMetadata toolMetadataOrNull(String toolName) {
        return toolName == null || toolName.isBlank()
            ? null : toolRegistry.getToolMetadata(toolName);
    }

    private List<BatchRecordSet> outputRecordSets(Object output,
                                                  String reference,
                                                  ToolMetadata toolMetadata) {
        Map<String, Object> rootAnalysisContext =
            mcpAnalysisContextAdapter.adapt(reference, toolMetadata, output);
        List<BatchRecordSet> governedSets = governedProjectionRecordSets(
            output, reference, rootAnalysisContext, false);
        if (!governedSets.isEmpty()) {
            return governedSets;
        }
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
        List<BatchRecordSet> externalizedSets = externalizedPreviewRecordSets(
            output, reference, toolMetadata, rootAnalysisContext);
        if (!externalizedSets.isEmpty()) {
            return externalizedSets;
        }
        List<BatchRecordSet> projectedSets = structuredDataProjector.project(output).stream()
            .map(dataset -> new BatchRecordSet(
                reference + dataset.path(), rootAnalysisContext, dataset.rows()))
            .toList();
        if (!projectedSets.isEmpty()) {
            return deduplicateProjectedRecordSets(projectedSets);
        }
        return governedProjectionRecordSets(output, reference, rootAnalysisContext, true);
    }

    /** Keeps one worker task for mirrored representations of the same projected records. */
    private List<BatchRecordSet> deduplicateProjectedRecordSets(List<BatchRecordSet> recordSets) {
        Map<String, BatchRecordSet> unique = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> aliases = new LinkedHashMap<>();
        for (BatchRecordSet recordSet : recordSets == null ? List.<BatchRecordSet>of() : recordSets) {
            if (recordSet == null || recordSet.records().isEmpty()) continue;
            String fingerprint = ModelProtocolJson.sha256Hex(
                ModelProtocolJson.compact(recordSet.records()));
            unique.putIfAbsent(fingerprint, recordSet);
            aliases.computeIfAbsent(fingerprint, ignored -> new LinkedHashSet<>())
                .add(recordSet.reference());
        }
        List<BatchRecordSet> result = new ArrayList<>();
        unique.forEach((fingerprint, recordSet) -> {
            List<String> sourceAliases = List.copyOf(aliases.get(fingerprint));
            if (sourceAliases.size() == 1) {
                result.add(recordSet);
                return;
            }
            Map<String, Object> context = new LinkedHashMap<>(recordSet.analysisContext());
            context.put("sourceAliases", sourceAliases);
            context.put("projectionDeduplicated", true);
            result.add(new BatchRecordSet(
                recordSet.reference(), Map.copyOf(context), recordSet.records()));
        });
        return List.copyOf(result);
    }

    private List<BatchRecordSet> governedProjectionRecordSets(Object output,
                                                              String reference,
                                                              Map<String, Object> rootAnalysisContext,
                                                              boolean includeFallback) {
        int maximumRecordChars = Math.max(1_000, recordAnalysisChunkMaxChars - 2_000);
        Map<String, Object> projection = includeFallback
            ? evidenceGovernanceBridge.analysisProjection(reference, output, maximumRecordChars)
            : evidenceGovernanceBridge.protocolAnalysisProjection(reference, output, maximumRecordChars);
        List<BatchRecordSet> sets = new ArrayList<>();
        Map<String, Object> preservationManifest = metadataOf(
            "sourceSchemaVersion", projection.get("sourceSchemaVersion"),
            "sourcePayloadPreserved", projection.get("sourcePayloadPreserved"),
            "sourcePayloadSha256", projection.get("sourcePayloadSha256"),
            "sourcePayloadChars", projection.get("sourcePayloadChars"),
            "authoritativePayloadMutated", projection.get("authoritativePayloadMutated"),
            "projectionContainsBusinessDataOnly",
                projection.get("projectionContainsBusinessDataOnly")
        );
        for (Map<String, Object> dataset : objectMapList(projection.get("datasets"))) {
            List<Map<String, Object>> records = objectMapList(dataset.get("records"));
            if (records.isEmpty()) continue;
            Map<String, Object> adapterContext = new LinkedHashMap<>(
                objectMap(dataset.get("analysisContext")));
            adapterContext.put("sourcePayloadPreservation", preservationManifest);
            sets.add(new BatchRecordSet(
                firstNonBlank(stringValue(dataset.get("datasetReference")), reference),
                mergeAnalysisContext(rootAnalysisContext, Map.copyOf(adapterContext)),
                records
            ));
        }
        return List.copyOf(sets);
    }

    private Map<String, Object> mergeAnalysisContext(Map<String, Object> root,
                                                     Map<String, Object> adapterContext) {
        if (adapterContext == null || adapterContext.isEmpty()) return root;
        Map<String, Object> merged = new LinkedHashMap<>(root == null ? Map.of() : root);
        merged.putAll(adapterContext);
        return Map.copyOf(merged);
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
                annotated.add(Collections.unmodifiableMap(row));
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
                annotated.add(Collections.unmodifiableMap(row));
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

    private RecordChunkPlan recordChunkPlan(
        List<Map<String, Object>> records,
        int maximumChunkRows,
        int maximumChunkChars
    ) {
        List<RecordRange> ranges = new ArrayList<>();
        int currentFrom = 0;
        int currentRows = 0;
        int currentChars = 0;
        long totalChars = 0;
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            int recordChars = Math.max(1, stringify(record).length());
            totalChars += recordChars;
            if (currentRows > 0 && (currentRows >= maximumChunkRows
                || currentChars + recordChars > maximumChunkChars)) {
                ranges.add(new RecordRange(currentFrom, index));
                currentFrom = index;
                currentRows = 0;
                currentChars = 0;
            }
            currentRows++;
            currentChars += recordChars;
        }
        if (currentRows > 0) {
            ranges.add(new RecordRange(currentFrom, records.size()));
        }
        boolean oversized = totalChars > maximumChunkChars
            || records.size() > maximumChunkRows;
        return new RecordChunkPlan(List.copyOf(ranges), oversized, totalChars);
    }

    private String summaryCheckpointInputSha256(String contentSha256,
                                                RuntimeAnalysisPosition position,
                                                Map<String, Object> governedContext,
                                                String query,
                                                boolean modelSummaryRequired) {
        return ModelProtocolJson.sha256Hex(Map.of(
            "bridgeSchemaVersion", RuntimeAnalysisSummaryProtocol.BRIDGE_SCHEMA_VERSION,
            "contentSha256", firstNonBlank(contentSha256, ""),
            "position", position == null ? Map.of() : position.toMap(),
            "governedContext", governedContext == null ? Map.of() : governedContext,
            "userObjective", firstNonBlank(query, ""),
            "modelSummaryRequired", modelSummaryRequired
        ));
    }

    private AnalysisSummaryResult restoreAnalysisSummaryCheckpoint(GovernanceIsolationScope scope,
                                                                    String checkpointKey,
                                                                    String inputSha256) {
        try {
            return analysisEvidenceSpillStore.readCheckpoint(scope, checkpointKey, inputSha256)
                .map(json -> {
                    try {
                        AnalysisSummaryResult result = objectMapper.readValue(json, AnalysisSummaryResult.class);
                        scope.requireSamePartition(result.isolationScope());
                        return result;
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .orElse(null);
        } catch (Exception ex) {
            log.warn("Analysis summary checkpoint is unusable and will be recomputed. checkpointKey={} error={}",
                checkpointKey, ex.getMessage());
            return null;
        }
    }

    private void persistAnalysisSummaryCheckpoint(GovernanceIsolationScope scope,
                                                  String checkpointKey,
                                                  String inputSha256,
                                                  AnalysisSummaryResult summary) {
        try {
            analysisEvidenceSpillStore.checkpoint(
                scope, checkpointKey, inputSha256, objectMapper.writeValueAsString(summary));
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Analysis summary checkpoint cannot be persisted losslessly: " + checkpointKey, ex);
        }
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
        boolean everyRecordReferenced = !coverage.iterative() && coverage.recordValueGroups().stream()
            .allMatch(values -> containsAnyConcreteValue(governedAnswer, values));
        if (everyRecordReferenced && !coverage.iterative() && coverage.sourceContentComplete()) {
            return governedAnswer;
        }
        long governedSummaryCount = coverage.summaryResults().stream()
            .filter(summary -> "MODEL_SUMMARY".equals(summary.outcome()))
            .filter(summary -> summary.content() != null && !summary.content().isBlank())
            .count();
        if (coverage.coverageComplete() && coverage.evidenceTraceComplete()
            && coverage.sourceContentComplete()
            && governedSummaryCount > 0) {
            if (metadata != null) {
                metadata.put("recordAnalysisCoverageAppendixApplied", false);
                metadata.put("recordAnalysisNarrativeCoverageApplied", true);
                metadata.put("recordAnalysisEveryRecordReferencedByModel", everyRecordReferenced);
                metadata.put("recordAnalysisGovernedSummaryCount", governedSummaryCount);
            }
            return governedAnswer;
        }
        if (metadata != null) {
            metadata.put("recordAnalysisCoverageAppendixApplied", false);
            metadata.put("recordAnalysisDataFallbackApplied", true);
            metadata.put("recordAnalysisEveryRecordReferencedByModel", everyRecordReferenced);
        }
        String limitation = !coverage.coverageComplete()
            ? "\n\n> 限制：部分已返回数据未完成分析，当前结论仅基于已处理数据。"
            : !coverage.sourceContentComplete()
                ? "\n\n> 限制：以上结果仅基于已返回的预览数据，不能代表完整源数据。"
                : "";
        return firstNonBlank(governedAnswer, "")
            + "\n\n## 已返回数据\n\n"
            + coverage.appendix()
            + limitation;
    }

    private String ensureGovernedNarrativeAnalysis(String answer,
                                                   RecordCoverageBundle coverage,
                                                   Map<String, Object> metadata) {
        boolean containsReturnedValue = coverage.recordValueGroups().stream()
            .anyMatch(values -> containsAnyConcreteValue(answer, values));
        if (hasNarrativeAnalysis(answer) && containsReturnedValue) {
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
        StringBuilder appendix = new StringBuilder(
            containsReturnedValue ? firstNonBlank(answer, "") : "");
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
            metadata.put("governedNarrativeAnalysisReplacedOperationalDraft", !containsReturnedValue);
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
        boolean evidenceTraceComplete,
        int rawReplayChunkCount,
        List<AnalysisSummaryResult> summaryResults,
        List<AnalysisSummaryResult> synthesisInputs
    ) {
        private static RecordCoverageBundle empty() {
            return new RecordCoverageBundle(
                "", "", List.of(), 0, 0, 0, false, true, true, true, 0, List.of(), List.of());
        }
    }

    private record RecordRange(int fromInclusive, int toExclusive) { }

    private record RecordChunkPlan(List<RecordRange> ranges,
                                   boolean oversized,
                                   long serializedChars) { }

    private record AnalysisDatasetTaskOutcome(
        AnalysisDatasetSummary summary,
        String status,
        String workerId,
        long durationMs,
        String error
    ) {
        private static AnalysisDatasetTaskOutcome completed(
            AnalysisDatasetSummary summary,
            String status,
            String workerId,
            long durationMs
        ) {
            return new AnalysisDatasetTaskOutcome(summary,
                firstNonBlankStatic(status, "SUCCESS"),
                firstNonBlankStatic(workerId, "unknown-worker"),
                Math.max(0L, durationMs), "");
        }

        private static AnalysisDatasetTaskOutcome failed(
            String status,
            String workerId,
            long durationMs,
            String error
        ) {
            return new AnalysisDatasetTaskOutcome(null,
                firstNonBlankStatic(status, "FAILED"),
                firstNonBlankStatic(workerId, "unknown-worker"),
                Math.max(0L, durationMs),
                firstNonBlankStatic(error, "unknown worker failure"));
        }

        private boolean success() {
            return summary != null && !"FAILED".equalsIgnoreCase(status);
        }

        private static String firstNonBlankStatic(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private static final class ParallelAnalysisSummaryBatch implements AutoCloseable {
        private final AnalysisTaskDispatcher.DispatchBatch dispatched;
        private final Map<String, AnalysisTask> tasksById;
        private final Map<String, String> taskIdsByDatasetReference;

        private ParallelAnalysisSummaryBatch(
            AnalysisTaskDispatcher.DispatchBatch dispatched,
            List<AnalysisTask> tasks,
            Map<String, String> taskIdsByDatasetReference
        ) {
            this.dispatched = dispatched;
            Map<String, AnalysisTask> indexedTasks = new LinkedHashMap<>();
            tasks.forEach(task -> indexedTasks.put(task.taskId(), task));
            this.tasksById = Map.copyOf(indexedTasks);
            this.taskIdsByDatasetReference = Map.copyOf(taskIdsByDatasetReference);
        }

        private static ParallelAnalysisSummaryBatch disabled() {
            return new ParallelAnalysisSummaryBatch(null, List.of(), Map.of());
        }

        private AnalysisDatasetTaskOutcome await(String datasetReference) {
            String taskId = taskIdsByDatasetReference.get(datasetReference);
            if (taskId == null || dispatched == null) {
                return AnalysisDatasetTaskOutcome.failed(
                    "MISSING", "driver", 0L, "missing dispatched analysis task");
            }
            AnalysisTask task = tasksById.get(taskId);
            AnalysisTaskResult result = dispatched.await(taskId);
            if (result == null || task == null
                || !task.taskId().equals(result.taskId())
                || !task.inputSha256().equals(result.inputSha256())
                || result.summary() == null
                || "FAILED".equalsIgnoreCase(result.status())) {
                log.warn("analysisTaskDriverFallback taskId={} status={} error={}", taskId,
                    result == null ? "MISSING" : result.status(),
                    result == null ? "missing worker result" : result.error());
                return AnalysisDatasetTaskOutcome.failed(
                    result == null ? "MISSING" : result.status(),
                    result == null ? "unknown-worker" : result.workerId(),
                    result == null ? 0L : result.durationMs(),
                    result == null ? "missing worker result" : result.error());
            }
            task.isolationScope().requireSamePartition(result.summary().isolationScope());
            return AnalysisDatasetTaskOutcome.completed(result.summary(), result.status(),
                result.workerId(), result.durationMs());
        }

        private boolean isParallel() {
            return dispatched != null
                && dispatched.taskCount() > 1
                && dispatched.workerCount() > 1;
        }

        private int taskCount() {
            return dispatched == null ? 0 : dispatched.taskCount();
        }

        private int workerCount() {
            return dispatched == null ? 0 : dispatched.workerCount();
        }

        private String mode() {
            return dispatched == null ? "NONE" : dispatched.mode();
        }

        @Override
        public void close() {
            if (dispatched != null) dispatched.close();
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
            return InterpretationPlanRuntime.StepReview.accepted(
                "Tool result reviewer was unavailable; preserving successful tool evidence.",
                Map.of(
                    "toolResultReviewRaw", preview(raw),
                    "toolResultReviewUnavailable", true
                )
            );
        }
        if (payload.containsKey("error")
            && firstObject(payload, "satisfied", "accepted", "sufficient") == null) {
            return InterpretationPlanRuntime.StepReview.accepted(
                "Tool result reviewer was unavailable; preserving successful tool evidence.",
                Map.of(
                    "toolResultReviewRaw", preview(raw),
                    "toolResultReviewUnavailable", true,
                    "toolResultReviewError", preview(stringify(payload.get("error")))
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
        prompt.append("- For every asset discovery result, including a single candidate, compare only returned routing metadata with the current target. Return at least one id present in the result under selected_asset_ids when satisfied=true, plus rejected_asset_ids and one asset_evaluations entry per candidate. Asset discovery proves routing eligibility, never target health or business state.\n");
        prompt.append("- Template retrieval scores and ordering are weak recall priors, never acceptance decisions. Semantically review every returned template candidate, including a single candidate. satisfied=true requires at least one returned id in selected_template_ids or an accept template_evaluations decision.\n");
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
                .append("\nPrompt preview truncated: false")
                .append("\nRuntime truncation applied: false");
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
        if (evaluation == null || !evaluation.valid()) {
            log.warn("InterpretationPlan evaluation rejected stage={} errors={} warnings={}",
                stage,
                evaluation == null || evaluation.errors() == null ? List.of() : evaluation.errors(),
                evaluation == null || evaluation.warnings() == null ? List.of() : evaluation.warnings());
        }
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
        List<Object> supersededMissingEvidence = new ArrayList<>();
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
            if (reason != null && !reason.isBlank()
                && (!diagnosticCoverageComplete || satisfiedTemplateExecutionEvidence(item))) {
                conclusions.add(reason);
            }
            if (item.get("iterationSufficient") != null) {
                explicitIterationDecision = true;
                iterationSufficient &= booleanValue(item.get("iterationSufficient"));
            }
        }
        nextActions.addAll(pendingEvidenceNextActions(toolEvidence));
        if (diagnosticRun != null && diagnosticCoverageComplete && result != null && result.success()) {
            // Per-step reviewers run before downstream dependencies. Their interim
            // "not executed yet" gaps are therefore stale once the Runtime-owned
            // diagnostic contract proves that every required check is covered by
            // successful execution evidence. Preserve them for audit, but never let
            // them override the authoritative completion state.
            supersededMissingEvidence.addAll(missingEvidence);
            missingEvidence.clear();
            nextActions.clear();
            iterationSufficient = true;
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
        snapshot.put("supersededMissingEvidence", supersededMissingEvidence);
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

    private boolean satisfiedTemplateExecutionEvidence(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        Map<String, Object> contract = asMap(evidence.get("templateExecutionReview"));
        return Boolean.TRUE.equals(booleanValue(contract.get("satisfied")))
            && Boolean.TRUE.equals(booleanValue(evidence.get("success")));
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

    List<Map<String, Object>> interpretationToolEvidence(
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
            List<Object> stepNextActions = new ArrayList<>();
            if (step.metadata() != null) {
                addEvidenceAnalysisItems(stepNextActions,
                    step.metadata().getOrDefault("nextActions", List.of()));
            }
            Object executionContractSource = step.toolExecution() != null
                && step.toolExecution().output() != null
                && step.toolExecution().output().getData() != null
                ? step.toolExecution().output().getData()
                : step.output();
            stepNextActions.addAll(discoveredExecutorActions(
                executionContractSource, step.toolName(), step.stepId()));
            item.put("nextActions", List.copyOf(stepNextActions));
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

    /**
     * Converts a discovery result's published execution contract into a deterministic
     * evidence-refinement action. The Runtime reads the executor role from returned metadata;
     * it does not infer an executor from business intent, template names, or tool-name families.
     */
    List<Map<String, Object>> discoveredExecutorActions(Object output,
                                                        String sourceTool,
                                                        Integer sourceStepId) {
        Set<String> executionTools = new LinkedHashSet<>();
        collectExecutionTools(output, executionTools, 0);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String executionTool : executionTools) {
            if (executionTool == null || executionTool.isBlank()
                || toolNames.sameToolName(sourceTool, executionTool)) {
                continue;
            }
            actions.add(metadataOf(
                "action", "execute_discovered_template",
                "tool", executionTool,
                "requiredExecution", true,
                "source", "tool_output_execution_contract",
                "discoveryStepId", sourceStepId,
                "dependsOnTools", sourceTool == null ? List.of() : List.of(sourceTool),
                "reason", "Discovery metadata declares an executor; discovery alone is not business evidence."
            ));
        }
        return List.copyOf(actions);
    }

    private void collectExecutionTools(Object value, Set<String> target, int depth) {
        if (value == null || target == null || depth > 10 || target.size() >= 16) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String field = key == null ? "" : String.valueOf(key);
                if (("executionTool".equals(field) || "execution_tool".equals(field))
                    && nested != null && !String.valueOf(nested).isBlank()) {
                    target.add(String.valueOf(nested).trim());
                } else {
                    collectExecutionTools(nested, target, depth + 1);
                }
            });
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                collectExecutionTools(item, target, depth + 1);
            }
        }
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
                boolean requiredExecution = booleanValue(firstObject(action,
                    "requiredExecution", "required_execution"));
                if (requestedTool != null
                    && !requiredExecution
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
        String scopedImplementation = toolNames.resolveMostSpecificAvailableTool(
            requestedTool, availableTools);
        if (scopedImplementation != null) {
            return scopedImplementation;
        }
        if (toolNames.isAbstractCapability(requestedTool)) {
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
            "evidenceContext", metadata == null
                ? Map.of()
                : metadata.getOrDefault("latestDagRepairEvidenceContext", Map.of()),
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
        String eventState = firstNonBlank(
            stringValue(repairEvent.get("eventState")), "APPLIED");
        boolean applied = "APPLIED".equalsIgnoreCase(eventState);
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            AGENT_RUN_ID_ATTRIBUTE,
            applied
                ? "Runtime restored and validated the planner DAG from the authoritative workflow contract ("
                    + repairCode + ")."
                : "Runtime restored the authoritative workflow topology, but the planner candidate remained invalid ("
                    + repairCode + "); deterministic Java scheduling will evaluate the pending Ready tools.",
            "interpretation_plan_repair",
            metadataOf(
                "type", "repair",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "dag_repair",
                "eventKind", "DAG_REPAIR",
                "eventState", eventState,
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
        return planAttemptRewriteSummary(nextAttempt, previousPlan, previousResult, List.of());
    }

    private String planAttemptRewriteSummary(
        int nextAttempt,
        InterpretationPlan previousPlan,
        InterpretationPlanRuntime.ExecutionResult previousResult,
        List<Map<String, Object>> evidenceHistory
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
            + ". Generate a new complete plan from this evidence, evaluate it, then execute it only if evaluation passes."
            + " Repair evidence context=" + stringify(repairEvidenceContext(evidenceHistory));
    }

    private Map<String, Object> repairEvidenceContext(List<Map<String, Object>> evidenceHistory) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> latest = evidenceHistory == null || evidenceHistory.isEmpty()
            ? Map.of()
            : evidenceHistory.get(evidenceHistory.size() - 1);
        context.put("contractVersion", "dag_repair_evidence_context_v1");
        context.put("evidenceIds", evidenceIdsFromSnapshot(latest));
        context.put("evidenceRelationIds", evidenceRelationIdsFromSnapshot(latest));
        context.put("hypothesisIds", hypothesisIdsFromSnapshot(latest));
        context.put("conclusion", latest.getOrDefault("conclusion", ""));
        context.put("missingEvidence", latest.getOrDefault("missingEvidence", List.of()));
        context.put("conflicts", latest.getOrDefault("conflicts", List.of()));
        context.put("nextActions", latest.getOrDefault("nextActions", List.of()));
        context.put("evidenceQuality", latest.getOrDefault("evidenceQuality", latest.getOrDefault("confidence", null)));
        context.put("sourceState", latest.getOrDefault("sourceState", latest.getOrDefault("overallStatus", "UNKNOWN")));
        return context;
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

    int initialRewriteLimit(int configuredMaxRewriteTimes,
                            EvidenceAugmentationPolicy.Outcome outcome,
                            boolean augmentationOverrideAvailable,
                            boolean executionRecoveryRequired,
                            boolean templateExecutionRetryRequested,
                            boolean toolsAvailable) {
        if (outcome == null || !outcome.continueLoop()) {
            return 0;
        }
        int configured = Math.max(0,
            Math.min(MAX_INTERPRETATION_PLAN_ATTEMPTS - 1, configuredMaxRewriteTimes));
        int limit = augmentationOverrideAvailable ? 1 : configured;
        if (executionRecoveryRequired) {
            limit = Math.max(limit, configured);
        }
        if (templateExecutionRetryRequested && toolsAvailable) {
            limit = Math.max(limit, 1);
        }
        return Math.min(MAX_INTERPRETATION_PLAN_ATTEMPTS - 1, limit);
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
        String originalUserQuery = stringValue(runtimeAttributes == null
            ? null
            : runtimeAttributes.get("originalUserQuery"));
        Map<String, Object> compiledArguments = originalUserQuery == null || originalUserQuery.isBlank()
            ? toolArguments.applyObservedTemplateContract(toolName, arguments, priorTraces)
            : toolArguments.applyObservedTemplateContract(
                toolName, arguments, priorTraces, originalUserQuery);
        Map<String, Object> safeArguments = new LinkedHashMap<>(compiledArguments);
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
                                                  int maxToolCalls,
                                                  String systemPrompt,
                                                  BooleanSupplier cancellationCheck) {
        Map<String, InteractionToolTrace> reviewedDiscoveryTraces = new LinkedHashMap<>();
        List<String> fallbackTools = new ArrayList<>();
        Set<String> completedTools = completedWorkflowToolsFromEvents(
            runtimeAttributes,
            workflowStateTracker.completedToolsFromTraces(traces)
        );
        fallbackTools.addAll(dependencyOrderedMandatoryFallbackTools(
            runtimeAttributes == null ? null : runtimeAttributes.get("authoritativeWorkflowDag"),
            runtimeAttributes == null ? null : runtimeAttributes.get("mcpWorkflow"),
            mandatoryTools,
            completedTools
        ));
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
            if (shouldSuppressLegacyMandatoryFallback(fallbackTool, metadata)) {
                metadata.put("mandatoryWorkflowFallbackSuppressed", true);
                metadata.put("mandatoryWorkflowFallbackSuppressedTool", fallbackTool);
                metadata.put("mandatoryWorkflowFallbackSuppressionReason",
                    "GOVERNED_DIAGNOSTIC_EXECUTOR_FAILED");
                metadata.put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                observations.add("Mandatory workflow fallback did not invoke " + fallbackTool
                    + " because the governed diagnostic DAG already attempted that executor and failed. "
                    + "A scalar legacy fallback cannot replace its reviewed multi-result execution contract.");
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
                mandatoryPredecessorTraces(
                    runtimeAttributes == null ? null : runtimeAttributes.get("authoritativeWorkflowDag"),
                    mandatoryTools,
                    fallbackTool,
                    traces
                );
            predecessorTraces = reviewedDependencyTraces(predecessorTraces, reviewedDiscoveryTraces);
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
            Map<String, Object> candidateArguments = authoritativeWorkflowCandidateInput(
                runtimeAttributes, fallbackTool);
            Map<String, Object> fallbackArguments = toolArguments.applyToolDefaults(
                fallbackTool,
                candidateArguments.isEmpty()
                    ? toolArguments.defaultToolArguments(fallbackTool, query, webSearchResultLimit)
                    : candidateArguments,
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
            if ("DENIED".equals(fallbackArguments.get(McpParamBindingResolver.STATUS_KEY))) {
                String contractCode = firstNonBlank(
                    stringValue(fallbackArguments.get(McpParamBindingResolver.CODE_KEY)),
                    "INVALID_TOOL_ARGUMENTS");
                String contractError = firstNonBlank(
                    stringValue(fallbackArguments.get(McpParamBindingResolver.ERROR_KEY)),
                    "The predecessor evidence did not authorize a compatible invocation contract.");
                metadata.put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                metadata.put("mandatoryWorkflowContractCode", contractCode);
                metadata.put("mandatoryWorkflowContractError", contractError);
                observations.add("Mandatory workflow fallback did not invoke " + fallbackTool
                    + " because its runtime-owned dependency contract was not executable: "
                    + contractCode + " - " + contractError);
                return;
            }
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
                reviewedDependencyTraces(traces, reviewedDiscoveryTraces),
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
                    reviewedDependencyTraces(traces, reviewedDiscoveryTraces),
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
            MandatoryCandidateReview semanticReview = reviewMandatoryDiscoveryCandidates(
                activeChatModel,
                query,
                systemPrompt,
                cancellationCheck,
                fallbackTool,
                fallbackArguments,
                execution.output(),
                runtimeAttributes
            );
            if (semanticReview.required()) {
                appendMandatorySemanticCandidateReview(metadata, semanticReview);
                observations.add("Mandatory workflow semantic candidate review: "
                    + stringify(semanticReview.auditMetadata()));
                if (!semanticReview.satisfied()) {
                    metadata.put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                    metadata.put("mandatoryWorkflowSemanticReviewBlocked", true);
                    metadata.put("mandatoryWorkflowSemanticReviewReason", semanticReview.reason());
                    return;
                }
                reviewedDiscoveryTraces.put(
                    fallbackTool,
                    projectedDependencyTrace(execution.trace(), semanticReview.projectedOutput(), semanticReview)
                );
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

    /**
     * Orders mandatory recovery calls by the authoritative DAG instead of by the
     * incidental order of a bound-tool collection. The latter may be sorted by a
     * repository, UI, or set implementation and is therefore not an execution
     * contract. Dependencies are tool identities published in workflow metadata;
     * no business domain or concrete tool name is encoded here.
     */
    List<String> dependencyOrderedMandatoryFallbackTools(Object authoritativeWorkflowDag,
                                                          Object mcpWorkflow,
                                                          List<String> mandatoryTools,
                                                          Set<String> completedTools) {
        List<String> missing = workflowTools.missingMandatoryTools(mandatoryTools, completedTools);
        if (missing.size() < 2) {
            return missing;
        }
        List<String> remaining = new ArrayList<>(missing);
        List<String> ordered = new ArrayList<>(missing.size());
        while (!remaining.isEmpty()) {
            String ready = remaining.stream()
                .filter(tool -> {
                    Set<String> dependencies = new LinkedHashSet<>();
                    List<String> authoritative = authoritativeWorkflowDependencies(
                        authoritativeWorkflowDag, tool);
                    if (authoritative != null) {
                        dependencies.addAll(authoritative);
                    }
                    dependencies.addAll(configuredWorkflowDependencies(mcpWorkflow, tool));
                    if (dependencies.isEmpty()) {
                        return true;
                    }
                    return dependencies.stream().noneMatch(dependency ->
                        remaining.stream().anyMatch(candidate ->
                            toolNames.sameToolName(dependency, candidate)));
                })
                .findFirst()
                .orElse(null);
            if (ready == null) {
                // The authoritative workflow is validated before orchestration. Keep
                // deterministic behavior if a corrupt/cyclic snapshot nevertheless
                // reaches recovery; predecessor review will fail closed.
                ordered.addAll(remaining);
                break;
            }
            ordered.add(ready);
            remaining.remove(ready);
        }
        return List.copyOf(ordered);
    }

    List<String> dependencyOrderedMandatoryFallbackTools(Object authoritativeWorkflowDag,
                                                          List<String> mandatoryTools,
                                                          Set<String> completedTools) {
        return dependencyOrderedMandatoryFallbackTools(
            authoritativeWorkflowDag, null, mandatoryTools, completedTools);
    }

    private List<String> configuredWorkflowDependencies(Object rawWorkflow, String toolName) {
        Map<String, Object> workflow = asMap(rawWorkflow);
        if (workflow.isEmpty() || toolName == null || toolName.isBlank()) {
            return List.of();
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        Object rawSteps = workflow.get("steps");
        if (rawSteps instanceof Iterable<?> values) {
            for (Object value : values) {
                Map<String, Object> step = asMap(value);
                String stepTool = firstNonBlank(
                    stringValue(step.get("tool")), stringValue(step.get("toolName")));
                if (stepTool == null) {
                    continue;
                }
                steps.add(step);
                for (Object alias : new Object[] {
                    step.get("step"), step.get("order"), step.get("id"),
                    step.get("name"), stepTool}) {
                    String text = stringValue(alias);
                    if (text != null && !text.isBlank()) {
                        aliases.putIfAbsent(text.trim().toLowerCase(Locale.ROOT), stepTool);
                    }
                }
            }
        }
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        for (Map<String, Object> step : steps) {
            String stepTool = firstNonBlank(
                stringValue(step.get("tool")), stringValue(step.get("toolName")));
            if (!toolNames.sameToolName(toolName, stepTool)) {
                continue;
            }
            collectConfiguredDependencies(dependencies,
                firstObject(step, "dependsOn", "depends_on"), aliases);
        }
        Map<String, Object> toolDependencies = asMap(firstObject(
            workflow, "toolDependencies", "tool_dependencies"));
        for (Map.Entry<String, Object> entry : toolDependencies.entrySet()) {
            if (!toolNames.sameToolName(toolName, entry.getKey())) {
                continue;
            }
            Map<String, Object> contract = asMap(entry.getValue());
            if (contract.isEmpty()) {
                collectConfiguredDependencies(dependencies, entry.getValue(), aliases);
            } else {
                collectConfiguredDependencies(dependencies,
                    firstObject(contract, "dependsOn", "depends_on"), aliases);
                collectConfiguredDependencies(dependencies,
                    firstObject(contract, "requiredDependsOn", "required_depends_on",
                        "requiredDependencies", "required_dependencies"), aliases);
            }
        }
        return List.copyOf(dependencies);
    }

    private void collectConfiguredDependencies(Set<String> target,
                                                Object rawDependencies,
                                                Map<String, String> aliases) {
        if (target == null || rawDependencies == null) {
            return;
        }
        Collection<?> values = rawDependencies instanceof Collection<?> collection
            ? collection : List.of(rawDependencies);
        for (Object value : values) {
            String dependency = stringValue(value);
            if (dependency == null || dependency.isBlank()) {
                continue;
            }
            target.add(aliases.getOrDefault(
                dependency.trim().toLowerCase(Locale.ROOT), dependency.trim()));
        }
    }

    MandatoryCandidateReview reviewMandatoryDiscoveryCandidates(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        BooleanSupplier cancellationCheck,
        String toolName,
        Map<String, Object> input,
        ToolOutput output,
        Map<String, Object> runtimeAttributes
    ) {
        boolean assetDiscovery = toolNames.isAssetDiscoveryToolName(toolName);
        boolean templateDiscovery = toolNames.isTemplateDiscoveryToolName(toolName);
        if (!assetDiscovery && !templateDiscovery) {
            return MandatoryCandidateReview.notRequired();
        }
        if (output == null || !output.isSuccess()) {
            return MandatoryCandidateReview.rejected(
                "candidate discovery did not return a successful ToolOutput", Map.of());
        }
        Map<String, Object> factMetadata = new LinkedHashMap<>();
        factMetadata.put("localDecisionPhase", "fact_check");
        factMetadata.put("localFactCheckSatisfied", true);
        factMetadata.put("localFactCheckHasEvidence", true);
        factMetadata.put("localFactCheckEvidenceType", assetDiscovery
            ? "asset_discovery" : "template_discovery");
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1, "mcp_tool", toolName,
            input == null ? Map.of() : new LinkedHashMap<>(input),
            List.of(), null, null
        );
        InterpretationPlanRuntime.StepExecution stepExecution = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", toolName, true, output.getData(), null,
            null, null, output.getExecutionTimeMs() == null ? 0L : output.getExecutionTimeMs(),
            factMetadata
        );
        InterpretationPlanRuntime.StepReview review = reviewInterpretationPlanToolResult(
            activeChatModel,
            query,
            systemPrompt,
            cancellationCheck,
            new InterpretationPlanRuntime.StepReviewRequest(
                null, step, stepExecution, Map.of(), 1, 1,
                runtimeAttributes == null ? null : stringValue(runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE))
            )
        );
        if (review == null) {
            return MandatoryCandidateReview.rejected(
                "model candidate reviewer returned no decision", Map.of()
            );
        }
        if (assetDiscovery) {
            EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
                new EvidenceBasedAssetCandidateEvaluator().evaluate(output.getData(), review.metadata());
            boolean accepted = evaluation.applied() && evaluation.selectedCount() > 0;
            Map<String, Object> audit = new LinkedHashMap<>(review.metadata());
            audit.put("reviewerSatisfied", review.satisfied());
            audit.put("candidateType", "ASSET");
            audit.put("candidateCount", evaluation.candidateCount());
            audit.put("selectedCount", evaluation.selectedCount());
            audit.put("selectedIds", evaluation.selectedIds());
            audit.put("projectionApplied", evaluation.applied());
            return accepted
                ? MandatoryCandidateReview.accepted(review.reason(), evaluation.output(), audit)
                : MandatoryCandidateReview.rejected(
                    "model review did not admit any asset id from the returned candidate set",
                    audit
                );
        }
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(output.getData(), review.metadata());
        boolean accepted = evaluation.applied() && evaluation.selectedCount() > 0;
        Map<String, Object> audit = new LinkedHashMap<>(review.metadata());
        audit.put("reviewerSatisfied", review.satisfied());
        audit.put("candidateType", "TEMPLATE");
        audit.put("candidateCount", evaluation.candidateCount());
        audit.put("selectedCount", evaluation.selectedCount());
        audit.put("selectedIds", evaluation.selectedIds());
        audit.put("projectionApplied", evaluation.applied());
        return accepted
            ? MandatoryCandidateReview.accepted(review.reason(), evaluation.output(), audit)
            : MandatoryCandidateReview.rejected(
                "model review did not admit any template id from the returned candidate set",
                audit
            );
    }

    private List<InteractionToolTrace> reviewedDependencyTraces(
        List<InteractionToolTrace> traces,
        Map<String, InteractionToolTrace> reviewedDiscoveryTraces
    ) {
        if (traces == null || traces.isEmpty() || reviewedDiscoveryTraces == null
            || reviewedDiscoveryTraces.isEmpty()) {
            return traces == null ? List.of() : traces;
        }
        List<InteractionToolTrace> projected = new ArrayList<>(traces.size());
        for (InteractionToolTrace trace : traces) {
            InteractionToolTrace replacement = trace == null ? null
                : reviewedDiscoveryTraces.entrySet().stream()
                    .filter(entry -> toolNames.sameToolName(entry.getKey(), trace.getToolName()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            projected.add(replacement == null ? trace : replacement);
        }
        return List.copyOf(projected);
    }

    private InteractionToolTrace projectedDependencyTrace(
        InteractionToolTrace original,
        Object projectedOutput,
        MandatoryCandidateReview review
    ) {
        Map<String, Object> runtimeMetadata = new LinkedHashMap<>(
            original == null || original.getRuntimeMetadata() == null
                ? Map.of() : original.getRuntimeMetadata());
        runtimeMetadata.put("semanticCandidateReviewSatisfied", true);
        runtimeMetadata.put("semanticCandidateReview", review.auditMetadata());
        return InteractionToolTrace.builder()
            .toolName(original == null ? null : original.getToolName())
            .displayName(original == null ? null : original.getDisplayName())
            .serviceId(original == null ? null : original.getServiceId())
            .serviceName(original == null ? null : original.getServiceName())
            .success(original == null || original.isSuccess())
            .input(original == null ? Map.of() : original.getInput())
            .output(stringify(projectedOutput))
            .errorMessage(original == null ? null : original.getErrorMessage())
            .durationMs(original == null ? null : original.getDurationMs())
            .startedAt(original == null ? null : original.getStartedAt())
            .finishedAt(original == null ? null : original.getFinishedAt())
            .runtimeMetadata(runtimeMetadata)
            .build();
    }

    @SuppressWarnings("unchecked")
    private void appendMandatorySemanticCandidateReview(Map<String, Object> metadata,
                                                        MandatoryCandidateReview review) {
        List<Map<String, Object>> reviews = metadata.get("mandatorySemanticCandidateReviews") instanceof List<?> existing
            ? new ArrayList<>((List<Map<String, Object>>) existing)
            : new ArrayList<>();
        Map<String, Object> audit = new LinkedHashMap<>(review.auditMetadata());
        audit.put("schemaVersion", "mandatory_semantic_candidate_review.v1");
        audit.put("satisfied", review.satisfied());
        audit.put("reason", review.reason());
        reviews.add(Map.copyOf(audit));
        metadata.put("mandatorySemanticCandidateReviews", List.copyOf(reviews));
    }

    record MandatoryCandidateReview(
        boolean required,
        boolean satisfied,
        String reason,
        Object projectedOutput,
        Map<String, Object> auditMetadata
    ) {
        MandatoryCandidateReview {
            auditMetadata = auditMetadata == null ? Map.of() : Map.copyOf(auditMetadata);
        }

        static MandatoryCandidateReview notRequired() {
            return new MandatoryCandidateReview(false, true, "not a candidate discovery tool", null, Map.of());
        }

        static MandatoryCandidateReview accepted(String reason, Object output, Map<String, Object> metadata) {
            return new MandatoryCandidateReview(true, true, reason, output, metadata);
        }

        static MandatoryCandidateReview rejected(String reason, Map<String, Object> metadata) {
            return new MandatoryCandidateReview(true, false, reason, null, metadata);
        }
    }

    boolean shouldSuppressLegacyMandatoryFallback(String fallbackTool,
                                                  Map<String, Object> metadata) {
        if (fallbackTool == null || fallbackTool.isBlank() || metadata == null
            || metadata.get("diagnosticRun") == null) {
            return false;
        }
        Object rawExecutions = metadata.get("interpretationPlanStepExecutions");
        if (!(rawExecutions instanceof Iterable<?> executions)) {
            return false;
        }
        for (Object rawExecution : executions) {
            if (!(rawExecution instanceof Map<?, ?> execution)) {
                continue;
            }
            String attemptedTool = stringValue(execution.get("toolName"));
            boolean failed = execution.containsKey("success")
                && !booleanValue(execution.get("success"));
            if (failed && toolNames.sameToolName(fallbackTool, attemptedTool)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    List<String> missingRequiredToolInputs(String toolName, Map<String, Object> arguments) {
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        Object rawCalls = firstObject(input, "calls", "toolCalls", "tool_calls");
        if (rawCalls instanceof List<?> calls && !calls.isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (int index = 0; index < calls.size(); index++) {
                Object rawCall = calls.get(index);
                if (!(rawCall instanceof Map<?, ?> call)) {
                    missing.add("calls[" + index + "]");
                    continue;
                }
                String childTool = firstNonBlank(
                    firstNonBlank(stringValue(call.get("toolName")), stringValue(call.get("tool_name"))),
                    toolName
                );
                Object rawChildArguments = firstObject(
                    new LinkedHashMap<>((Map<String, Object>) call), "arguments", "input");
                if (!(rawChildArguments instanceof Map<?, ?> childArguments)) {
                    missing.add("calls[" + index + "].arguments");
                    continue;
                }
                for (String childMissing : missingRequiredToolInputs(
                    childTool, new LinkedHashMap<>((Map<String, Object>) childArguments))) {
                    missing.add("calls[" + index + "].arguments." + childMissing);
                }
            }
            return List.copyOf(missing);
        }
        ToolMetadata toolMetadata = toolRegistry.getToolMetadata(toolName);
        if (toolMetadata == null || toolMetadata.getParameters() == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (ToolParameter parameter : toolMetadata.getParameters()) {
            if (parameter == null || !parameter.isRequired()
                || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            Object value = requiredToolInputValue(input, parameter.getName());
            if (value == null
                || value instanceof CharSequence text && text.toString().isBlank()) {
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

    List<InteractionToolTrace> mandatoryPredecessorTraces(Object authoritativeWorkflowDag,
                                                           List<String> mandatoryTools,
                                                           String fallbackTool,
                                                           List<InteractionToolTrace> traces) {
        if (mandatoryTools == null || mandatoryTools.isEmpty()
            || fallbackTool == null || traces == null || traces.isEmpty()) {
            return List.of();
        }
        List<String> configuredDependencies = authoritativeWorkflowDependencies(
            authoritativeWorkflowDag, fallbackTool);
        if (configuredDependencies != null) {
            return successfulToolTraces(configuredDependencies, traces);
        }

        // Backward compatibility for callers without a persisted DAG. A configured
        // authoritative DAG always wins; list order must never create dependencies.
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
        return successfulToolTraces(predecessors, traces);
    }

    private List<InteractionToolTrace> successfulToolTraces(List<String> dependencyTools,
                                                             List<InteractionToolTrace> traces) {
        if (dependencyTools == null || dependencyTools.isEmpty() || traces == null || traces.isEmpty()) {
            return List.of();
        }
        return traces.stream()
            .filter(Objects::nonNull)
            .filter(InteractionToolTrace::isSuccess)
            .filter(trace -> trace.getOutput() != null && !trace.getOutput().isBlank())
            .filter(trace -> dependencyTools.stream()
                .anyMatch(tool -> toolNames.sameToolName(tool, trace.getToolName())))
            .toList();
    }

    /**
     * Returns null only when no authoritative node exists for the requested tool.
     * An empty list means the configured node deliberately has no dependencies.
     */
    private List<String> authoritativeWorkflowDependencies(Object rawDag, String fallbackTool) {
        if (!(rawDag instanceof Iterable<?> nodes) || fallbackTool == null || fallbackTool.isBlank()) {
            return null;
        }
        for (Object rawNode : nodes) {
            Map<String, Object> node = asMap(rawNode);
            String nodeTool = firstNonBlank(
                stringValue(node.get("tool")), stringValue(node.get("toolName")));
            if (!toolNames.sameToolName(fallbackTool, nodeTool)) {
                continue;
            }
            Object rawDependencies = firstObject(node, "dependsOnTools", "depends_on_tools", "dependsOn");
            if (!(rawDependencies instanceof Iterable<?> dependencies)) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (Object dependency : dependencies) {
                String value = stringValue(dependency);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        }
        return null;
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
        Integer assetCount = assetDiscoveryResultCount(toolName, output.getData());
        if (assetCount != null) {
            boolean satisfied = assetCount > 0;
            review.put("satisfied", satisfied);
            review.put("resultCode", satisfied ? "ASSET_MATCHED" : "NO_MATCHING_ASSET");
            review.put("returnedCount", assetCount);
            review.put("reason", satisfied
                ? "Asset discovery returned at least one candidate for semantic model review."
                : "Asset discovery completed but returned no candidate; dependent workflow tools are blocked.");
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

    private Integer assetDiscoveryResultCount(String toolName, Object data) {
        if (!toolNames.isAssetDiscoveryToolName(toolName)) {
            return null;
        }
        return discoveryResultCount(data, "assets", 0);
    }

    private Integer templateDiscoveryResultCount(String toolName, Object data) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        if (!(normalized.contains("template_query") || normalized.contains("template_search"))) {
            return null;
        }
        return discoveryResultCount(data, "templates", 0);
    }

    private Integer discoveryResultCount(Object value, String collectionKey, int depth) {
        if (value == null || depth > 5) {
            return null;
        }
        if (value instanceof String text) {
            Map<String, Object> parsed = asMap(text);
            return parsed.isEmpty() ? null : discoveryResultCount(parsed, collectionKey, depth + 1);
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = asMap(raw);
        Integer explicit = integerValue(map.get("returnedCount"));
        if (explicit != null) {
            return explicit;
        }
        if (map.get(collectionKey) instanceof java.util.Collection<?> candidates) {
            return candidates.size();
        }
        for (String key : List.of("preview", "structuredContent", "data", "result", "payload", "body", "output")) {
            Integer nested = discoveryResultCount(map.get(key), collectionKey, depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private Map<String, Map<String, Object>> authoritativeWorkflowCandidateInputs(
        InterpretationPlan plan,
        List<Map<String, Object>> authoritativeWorkflowDag
    ) {
        if (plan == null || plan.plan() == null || plan.plan().steps() == null
            || authoritativeWorkflowDag == null || authoritativeWorkflowDag.isEmpty()) {
            return Map.of();
        }
        List<String> configuredTools = authoritativeWorkflowDag.stream()
            .map(node -> firstNonBlank(stringValue(node.get("tool")), stringValue(node.get("toolName"))))
            .filter(Objects::nonNull)
            .toList();
        Map<String, Map<String, Object>> inputs = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : plan.plan().steps()) {
            if (step == null || !step.mcpToolAction() || step.toolName() == null || step.input() == null) {
                continue;
            }
            String configuredTool = configuredTools.stream()
                .filter(tool -> toolNames.sameToolName(tool, step.toolName()))
                .findFirst()
                .orElse(null);
            if (configuredTool != null) {
                inputs.putIfAbsent(configuredTool, new LinkedHashMap<>(step.input()));
            }
        }
        return inputs.isEmpty() ? Map.of() : Map.copyOf(inputs);
    }

    private Map<String, Object> authoritativeWorkflowCandidateInput(
        Map<String, Object> runtimeAttributes,
        String toolName
    ) {
        if (runtimeAttributes == null || toolName == null
            || !(runtimeAttributes.get("authoritativeWorkflowCandidateInputs") instanceof Map<?, ?> rawInputs)) {
            return Map.of();
        }
        for (Map.Entry<?, ?> entry : rawInputs.entrySet()) {
            if (!toolNames.sameToolName(toolName, stringValue(entry.getKey()))
                || !(entry.getValue() instanceof Map<?, ?> rawInput)) {
                continue;
            }
            return new LinkedHashMap<>(asStringObjectMap(rawInput));
        }
        return Map.of();
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

    public record ToolCallExecution(
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
