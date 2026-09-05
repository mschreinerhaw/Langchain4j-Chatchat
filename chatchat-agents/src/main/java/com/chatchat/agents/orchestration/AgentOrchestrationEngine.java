package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.analysis.context.ContextCompressionEnvelope;
import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;
import com.chatchat.agents.orchestration.analysis.contract.SemanticInsightContractProvider;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisRecordChunkPlanner;
import com.chatchat.agents.orchestration.analysis.dataset.StructuredDataProjector;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDatasetWorker;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDatasetExecutionPort;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDatasetActivityExecutor;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisWorkerRetryPolicy;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisProgressRecorder;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator;
import com.chatchat.agents.orchestration.analysis.dispatch.LocalAnalysisTaskDispatcher;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.agents.orchestration.analysis.checkpoint.AnalysisSummaryCheckpointService;
import com.chatchat.agents.orchestration.analysis.worker.AnalysisSummaryGovernanceBridge;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisSummaryGovernanceCoordinator;
import com.chatchat.agents.orchestration.analysis.reducer.HierarchicalAnalysisReducer;
import com.chatchat.agents.orchestration.analysis.semantic.SemanticClaimCoordinator;
import com.chatchat.agents.orchestration.analysis.loop.AnalysisLoopCoordinator;
import com.chatchat.agents.orchestration.analysis.loop.AnalysisRefinementCoordinator;
import com.chatchat.agents.orchestration.analysis.driver.AnalysisSynthesisCoordinator;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.presentation.AgentLifecyclePresentationPolicy;
import com.chatchat.agents.evidence.normalization.EvidenceSource;
import com.chatchat.agents.evidence.graph.EvidenceGraph;
import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizer;
import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizationPort;
import com.chatchat.agents.orchestration.evidence.ContextEvidenceAggregator;
import com.chatchat.agents.orchestration.evidence.InterpretationPlanEvidenceAnalyzer;
import com.chatchat.agents.orchestration.evidence.AgentToolResultFactExtractor;
import com.chatchat.agents.orchestration.evidence.AgentEvidenceGraphService;
import com.chatchat.agents.orchestration.evidence.EvidenceTrustEvaluator;
import com.chatchat.agents.orchestration.evidence.RecoveredBatchEvidenceBridge;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.orchestration.model.DeadlineAwareChatModel;
import com.chatchat.agents.orchestration.model.MeteredChatModel;
import com.chatchat.agents.orchestration.planning.model.AgentContextBudget;
import com.chatchat.agents.orchestration.planning.validation.AgentPlanBudgetPolicy;
import com.chatchat.agents.orchestration.planning.model.AgentDecision;
import com.chatchat.agents.orchestration.planning.validation.AgentRuntimeGuard;
import com.chatchat.agents.orchestration.planning.execution.PlanExecutionResultCoordinator;
import com.chatchat.agents.orchestration.planning.execution.PlanExecutionObservationCoordinator;
import com.chatchat.agents.orchestration.planning.validation.RuntimeFunctionCallingPolicy;
import com.chatchat.agents.orchestration.planning.evolution.AgentPlanEvolutionAuditor;
import com.chatchat.agents.orchestration.planning.snapshot.InterpretationPlanSnapshotService;
import com.chatchat.agents.orchestration.planning.execution.AgentPlanExecutionBridge;
import com.chatchat.agents.orchestration.planning.evolution.AgentPlanPhaseActivityCoordinator;
import com.chatchat.agents.orchestration.lifecycle.AgentRunLifecycleCoordinator;
import com.chatchat.agents.orchestration.lifecycle.AgentRuntimeAttributeCompiler;
import com.chatchat.agents.orchestration.lifecycle.AgentRunScopeBinder;
import com.chatchat.agents.orchestration.retrieval.McpParamBindingResolver;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedContextParameterBridge;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedRetrievalBridge;
import com.chatchat.agents.tool.RegistryMcpCapabilityHierarchy;
import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolExecutor;
import com.chatchat.agents.orchestration.tool.AgentToolCallCoordinator;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.orchestration.tool.McpAnalysisContextAdapter;
import com.chatchat.agents.orchestration.tool.ToolCallFingerprint;
import com.chatchat.agents.orchestration.tool.ToolObservationBuilder;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowStatePort;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowStateTracker;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowToolResolver;
import com.chatchat.agents.orchestration.workflow.MandatoryWorkflowResultReviewer;
import com.chatchat.agents.orchestration.workflow.MandatoryWorkflowRecoveryPolicy;
import com.chatchat.agents.orchestration.workflow.MandatoryWorkflowRecoveryCoordinator;
import com.chatchat.agents.orchestration.workflow.MandatoryWorkflowTopology;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
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
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.answer.DefaultAgentAnswerReviewer;
import com.chatchat.agents.runtime.observation.DefaultAgentObservationPipeline;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;
import com.chatchat.common.runtime.protocol.RuntimeProtocolRegistry;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressReporter;
import com.chatchat.common.runtime.summary.spi.ModelSummaryReducer;
import com.chatchat.agents.orchestration.protocol.RuntimeProtocolDefaults;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.diagnostic.DiagnosticRun;
import com.chatchat.agents.runtime.plan.diagnostic.DiagnosticRunStateMachine;
import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;
import com.chatchat.agents.runtime.plan.InterpretationPlanRewriter;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.LocalPlanToolExecutionPort;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionPort;
import com.chatchat.agents.runtime.plan.execution.LocalPlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;
import com.chatchat.agents.runtime.plan.execution.AgentPlanSuspendedException;
import com.chatchat.agents.runtime.plan.execution.AgentRunExecutionSlice;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;
import com.chatchat.agents.runtime.plan.execution.ResumableAgentRunExecutor;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionPhaseHandler;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepFinalizationCommand;
import com.chatchat.agents.runtime.plan.execution.PreparedPlanStep;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceCommand;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceResult;
import com.chatchat.agents.runtime.plan.persistence.InterpretationPlanStore;
import com.chatchat.agents.runtime.plan.persistence.NodeAttemptStore;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelViolationException;
import com.chatchat.agents.runtime.plan.selection.EvidenceBasedAssetCandidateEvaluator;
import com.chatchat.agents.runtime.plan.selection.EvidenceBasedTemplateCandidateEvaluator;
import com.chatchat.agents.runtime.plan.selection.RetrievalQualityGate;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.knowledge.template.TemplateMatchAnalysis;
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
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/**
 * Agent orchestrator with tool planning and execution loop.
 */
@Slf4j
class AgentOrchestrationEngine implements AgentRunExecutor, ResumableAgentRunExecutor,
    AnalysisDatasetExecutionPort,
    PlanExecutionPhaseHandler {

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
    private final AgentPlanExecutionBridge planExecutionBridge;
    private final AgentPlanPhaseActivityCoordinator planPhaseActivities;
    private final ToolRegistry toolRegistry;
    private final ToolRuntimeService toolRuntimeService;
    private PlanToolExecutionPort planToolExecutionPort;
    private PlanDagControlPort planDagControlPort;
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
    private final AgentRuntimeAttributeCompiler runtimeAttributeCompiler;
    private final AgentPlanningPort planner;
    private final AgentRunResultAdapter runResultAdapter;
    private final SemanticClaimCoordinator semanticClaimCoordinator;
    private final AnalysisLoopCoordinator analysisLoopCoordinator;
    private final AnalysisRefinementCoordinator analysisRefinementCoordinator;
    private final PlanExecutionResultCoordinator planExecutionResultCoordinator;
    private final PlanExecutionObservationCoordinator planExecutionObservationCoordinator;
    private final AgentRunScopeBinder runScopeBinder;
    private final AgentRunLifecycleCoordinator runLifecycle;
    private final AgentPlanEvolutionAuditor planEvolutionAuditor;
    private final InterpretationPlanEvidenceAnalyzer planEvidenceAnalyzer;
    private final InterpretationPlanSnapshotService planSnapshotService;
    private ToolObservationBuilder toolObservationBuilder;
    private final AgentChatModelResolver chatModelResolver;
    private final AgentToolNameResolver toolNames;
    private final AgentToolArgumentResolver toolArguments;
    private final AgentToolExecutor toolExecutor;
    private final AgentToolCallCoordinator toolCallCoordinator;
    private final MandatoryWorkflowResultReviewer mandatoryWorkflowResultReviewer;
    private final AgentWorkflowToolResolver workflowTools;
    private final MandatoryWorkflowTopology mandatoryWorkflowTopology;
    private final MandatoryWorkflowRecoveryPolicy mandatoryWorkflowRecoveryPolicy;
    private final MandatoryWorkflowRecoveryCoordinator mandatoryWorkflowRecoveryCoordinator;
    private final ModelAssistedRetrievalBridge modelAssistedRetrievalBridge;
    private final ModelAssistedContextParameterBridge modelAssistedContextParameterBridge;
    private final AnswerCandidateCollector answerCandidateCollector = new AnswerCandidateCollector();
    private final AgentWorkflowStatePort workflowStateTracker;
    private final AgentAnswerFinalizationPort answerFinalizer;
    private final EvidenceAugmentationPolicy evidenceAugmentationPolicy = new EvidenceAugmentationPolicy();
    private final AgentContextBudget contextBudget;
    private final int recordAnalysisChunkMaxChars;
    private final int recordAnalysisChunkMaxRows;
    private final int analysisSpillThresholdBytes;
    private final AgentRuntimeProperties agentRuntimeProperties;
    private final int analysisSummaryWorkerCount;
    private final int analysisSummaryWorkerMaxRetries;
    private final long analysisSummaryWorkerHeartbeatIntervalMs;
    private final long analysisSummaryWorkerHeartbeatTimeoutMs;
    private final ContextTokenEstimator contextTokenEstimator = new ContextTokenEstimator();
    private final AnalysisRecordChunkPlanner recordChunkPlanner;
    private final AnalysisSummaryCheckpointService summaryCheckpointService;
    private final AnalysisSummaryGovernanceCoordinator summaryGovernanceCoordinator;
    private final AnalysisSynthesisCoordinator analysisSynthesisCoordinator;
    private final AnalysisCoverageCoordinator analysisCoverageCoordinator;
    private final AnalysisDatasetWorker analysisDatasetWorker;
    private final AnalysisDatasetActivityExecutor analysisDatasetActivityExecutor;
    private final AnalysisProgressRecorder analysisProgressRecorder;
    private final AnalysisDispatchCoordinator analysisDispatchCoordinator;
    private final AnalysisEvidenceCoordinator analysisEvidenceCoordinator;
    private final ContextEvidenceAggregator contextEvidenceAggregator = new ContextEvidenceAggregator();
    private final AgentToolResultFactExtractor toolResultFactExtractor;
    private final AgentEvidenceGraphService evidenceGraphService;
    private DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> analysisSummaryGovernanceBridge =
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
    private ModelSummaryDispatcher<AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult>
        analysisTaskDispatcher;
    public AgentOrchestrationEngine(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig) {
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            new EvidenceTrustEvaluator(), new InMemoryAgentRunStore(), new DefaultAgentObservationPipeline(),
            new DefaultAgentAnswerReviewer(objectMapper));
    }
    public AgentOrchestrationEngine(ChatModel chatModel,
                             ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             ObjectMapper objectMapper,
                             ModelsConfig modelsConfig,
                             EvidenceTrustEvaluator evidenceTrustEvaluator) {
        this(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, new InMemoryAgentRunStore(), new DefaultAgentObservationPipeline(),
            new DefaultAgentAnswerReviewer(objectMapper));
    }

    public AgentOrchestrationEngine(ChatModel chatModel,
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

    public AgentOrchestrationEngine(ChatModel chatModel,
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

    public AgentOrchestrationEngine(ChatModel chatModel,
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

    public AgentOrchestrationEngine(ChatModel chatModel,
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
    public AgentOrchestrationEngine(ChatModel chatModel,
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
        this.planToolExecutionPort = new LocalPlanToolExecutionPort(toolRuntimeService);
        this.planDagControlPort = new LocalPlanDagControlPort();
        this.objectMapper = objectMapper;
        this.runtimeAttributeCompiler = new AgentRuntimeAttributeCompiler(
            runtimeGuard, () -> dagGovernanceContractProvider, AGENT_RUN_ID_ATTRIBUTE,
            AGENT_MAX_STEPS_ATTRIBUTE, AGENT_MAX_TOOL_CALLS_ATTRIBUTE, AGENT_TIMEOUT_MS_ATTRIBUTE);
        this.planExecutionBridge = new AgentPlanExecutionBridge(objectMapper, AGENT_CANCELLATION_ATTRIBUTE);
        this.toolResultFactExtractor = new AgentToolResultFactExtractor(objectMapper);
        this.recordChunkPlanner = new AnalysisRecordChunkPlanner(objectMapper);
        this.summaryCheckpointService = new AnalysisSummaryCheckpointService(
            objectMapper, this.analysisEvidenceSpillStore);
        this.evidenceGraphService = new AgentEvidenceGraphService(objectMapper);
        this.mcpAnalysisContextAdapter = RuntimeProtocolDefaults.analysisContext(objectMapper);
        this.evidenceTrustEvaluator = evidenceTrustEvaluator == null ? new EvidenceTrustEvaluator() : evidenceTrustEvaluator;
        this.runStore = runStore == null ? new InMemoryAgentRunStore() : runStore;
        this.observationPipeline = observationPipeline == null ? new DefaultAgentObservationPipeline() : observationPipeline;
        AgentAnswerReviewer resolvedAnswerReviewer = answerReviewer == null ? new DefaultAgentAnswerReviewer(objectMapper) : answerReviewer;
        this.planner = new AgentPlanner(toolRegistry, objectMapper, agentRuntimeProperties);
        this.runResultAdapter = new AgentRunResultAdapter(this.runStore, this.observationPipeline);
        this.semanticClaimCoordinator = new SemanticClaimCoordinator(
            this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE);
        this.analysisLoopCoordinator = new AnalysisLoopCoordinator(
            this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE);
        this.analysisProgressRecorder = new AnalysisProgressRecorder(
            this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE);
        this.summaryGovernanceCoordinator = new AnalysisSummaryGovernanceCoordinator(
            this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE);
        this.analysisSynthesisCoordinator = new AnalysisSynthesisCoordinator(
            this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE, this.summaryGovernanceCoordinator,
            this.deterministicInsightEngine, this.answerCandidateCollector,
            this.hierarchicalAnalysisReducer);
        this.analysisDatasetWorker = new AnalysisDatasetWorker(
            this.recordChunkPlanner, this.summaryCheckpointService,
            this.analysisWorkerRetryPolicy, this.workerDatasetReducer,
            this.analysisSummaryGovernanceBridge, this.analysisEvidenceSpillStore);
        this.runScopeBinder = new AgentRunScopeBinder();
        this.runLifecycle = new AgentRunLifecycleCoordinator(this.runStore, this.runResultAdapter);
        this.planEvolutionAuditor =
            new AgentPlanEvolutionAuditor(this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE);
        this.toolObservationBuilder = new ToolObservationBuilder(this.evidenceTrustEvaluator);
        this.chatModelResolver = new AgentChatModelResolver(chatModel, modelsConfig);
        this.analysisDatasetActivityExecutor = new AnalysisDatasetActivityExecutor(
            this.chatModelResolver, this.analysisDatasetWorker);
        this.toolNames = new AgentToolNameResolver(new RegistryMcpCapabilityHierarchy(toolRegistry));
        this.analysisRefinementCoordinator = new AnalysisRefinementCoordinator(
            this.toolNames, MAX_INTERPRETATION_PLAN_ATTEMPTS);
        this.planExecutionResultCoordinator = new PlanExecutionResultCoordinator();
        this.planExecutionObservationCoordinator = new PlanExecutionObservationCoordinator(
            this.objectMapper, this.toolObservationBuilder);
        this.planEvidenceAnalyzer = new InterpretationPlanEvidenceAnalyzer(
            this.toolResultFactExtractor,
            this.evidenceGraphService,
            this.toolNames,
            this.runResultAdapter,
            AGENT_RUN_ID_ATTRIBUTE
        );
        this.toolArguments = new AgentToolArgumentResolver(this.toolNames, WEB_SEARCH_REFERENCE_LIMIT, this.toolRegistry);
        this.toolExecutor = new AgentToolExecutor(
            this.toolRegistry, this.toolRuntimeService, this.toolArguments, this.toolObservationBuilder);
        this.toolCallCoordinator = new AgentToolCallCoordinator(
            this.toolExecutor, this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE);
        this.mandatoryWorkflowResultReviewer = new MandatoryWorkflowResultReviewer(
            this.toolNames, this.toolResultFactExtractor, this.toolObservationBuilder, objectMapper);
        this.workflowTools = new AgentWorkflowToolResolver(this.toolNames);
        this.mandatoryWorkflowTopology = new MandatoryWorkflowTopology(this.toolNames, this.workflowTools);
        this.mandatoryWorkflowRecoveryPolicy = new MandatoryWorkflowRecoveryPolicy(toolRegistry, this.toolNames);
        this.modelAssistedRetrievalBridge = new ModelAssistedRetrievalBridge(this.toolRegistry, objectMapper);
        this.modelAssistedContextParameterBridge =
            new ModelAssistedContextParameterBridge(this.toolRegistry, objectMapper);
        this.planPhaseActivities = new AgentPlanPhaseActivityCoordinator(
            toolRegistry, toolRuntimeService, this.runStore, phaseActivityOperations());
        this.answerFinalizer = new AgentAnswerFinalizer(
            resolvedAnswerReviewer,
            this.runtimeGuard,
            modelsConfig,
            toolRegistry,
            toolRuntimeService,
            objectMapper,
            agentRuntimeProperties
        );
        this.mandatoryWorkflowRecoveryCoordinator = new MandatoryWorkflowRecoveryCoordinator(
            this.toolNames, this.toolArguments, this.workflowTools, this.workflowStateTracker,
            this.mandatoryWorkflowTopology, this.mandatoryWorkflowRecoveryPolicy,
            this.mandatoryWorkflowResultReviewer, this.modelAssistedRetrievalBridge,
            this.answerFinalizer, this.runStore, this.objectMapper);
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
        this.agentRuntimeProperties = resolvedRuntimeProperties;
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
        this.analysisEvidenceCoordinator = new AnalysisEvidenceCoordinator(
            this.toolRegistry, this.toolRuntimeService, this.structuredDataProjector,
            this.recordChunkPlanner, this.recordAnalysisChunkMaxChars,
            this.mcpAnalysisContextAdapter, this.evidenceGovernanceBridge,
            this.semanticInsightContractProvider);
        this.analysisDispatchCoordinator = new AnalysisDispatchCoordinator(
            this.analysisDatasetWorker, this.analysisProgressRecorder,
            new AnalysisDispatchCoordinator.Configuration(
                this.recordAnalysisChunkMaxRows, this.recordAnalysisChunkMaxChars,
                this.analysisSpillThresholdBytes, this.analysisSummaryWorkerMaxRetries,
                this.analysisSummaryWorkerHeartbeatIntervalMs,
                this.analysisSummaryWorkerHeartbeatTimeoutMs),
            this.analysisSummaryGovernanceBridge, this.analysisTaskDispatcher);
        this.analysisCoverageCoordinator = new AnalysisCoverageCoordinator(
            this.runResultAdapter, AGENT_RUN_ID_ATTRIBUTE, this.analysisEvidenceCoordinator,
            this.analysisDispatchCoordinator, this.deterministicInsightEngine,
            this.analysisSynthesisCoordinator, this.analysisEvidenceSpillStore,
            new AnalysisCoverageCoordinator.Configuration(
                this.analysisSummaryWorkerMaxRetries,
                this.analysisSummaryWorkerHeartbeatIntervalMs,
                this.analysisSummaryWorkerHeartbeatTimeoutMs));
        InterpretationPlanStore resolvedPlanStore = interpretationPlanStore == null && this.runStore instanceof InterpretationPlanStore store
            ? store
            : interpretationPlanStore;
        this.planSnapshotService = new InterpretationPlanSnapshotService(
            resolvedPlanStore, AGENT_RUN_ID_ATTRIBUTE);
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
        this.analysisEvidenceCoordinator.setSemanticInsightContractProvider(
            this.semanticInsightContractProvider);
    }

    /** Production supplies the database-backed node attempt journal. */
    @Autowired(required = false)
    public void setNodeAttemptStore(NodeAttemptStore nodeAttemptStore) {
        this.nodeAttemptStore = nodeAttemptStore;
    }

    /** Production Temporal configuration replaces the in-process plan tool execution boundary. */
    @Autowired(required = false)
    public void setPlanToolExecutionPort(PlanToolExecutionPort planToolExecutionPort) {
        if (planToolExecutionPort != null) {
            this.planToolExecutionPort = planToolExecutionPort;
        }
    }

    /** Production Temporal configuration persists Ready-node and commit-barrier state. */
    @Autowired(required = false)
    public void setPlanDagControlPort(PlanDagControlPort planDagControlPort) {
        if (planDagControlPort != null) {
            this.planDagControlPort = planDagControlPort;
        }
    }

    /** Production supplies the lossless RocksDB overflow/checkpoint store. */
    @Autowired(required = false)
    public void setAnalysisEvidenceSpillStore(AnalysisEvidenceSpillStore spillStore) {
        this.analysisEvidenceSpillStore = spillStore == null
            ? AnalysisEvidenceSpillStore.disabled()
            : spillStore;
        this.summaryCheckpointService.setStore(this.analysisEvidenceSpillStore);
        this.analysisDatasetWorker.setSpillStore(this.analysisEvidenceSpillStore);
        this.analysisCoverageCoordinator.setSpillStore(this.analysisEvidenceSpillStore);
    }

    /** Replaces local workers with a distributed task dispatcher without changing Driver orchestration. */
    public void setAnalysisSummaryProtocol(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol
    ) {
        if (protocol != null) {
            this.analysisSummaryGovernanceBridge = protocol;
            this.summaryGovernanceCoordinator.setProtocol(protocol);
            this.analysisDatasetWorker.setSummaryProtocol(protocol);
            this.analysisDispatchCoordinator.setSummaryProtocol(protocol);
            this.answerFinalizer.setAnalysisSummaryProtocol(protocol);
        }
    }

    /** Replaces local workers with a distributed task dispatcher without changing Driver orchestration. */
    @Autowired(required = false)
    public void setModelSummaryDispatcher(
        ModelSummaryDispatcher<AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> dispatcher
    ) {
        if (dispatcher != null) {
            this.analysisTaskDispatcher = dispatcher;
            this.analysisDispatchCoordinator.setDispatcher(dispatcher);
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
        return execute(runScopeBinder.bind(request, scope));
    }


    @Override
    public AgentRunResult execute(AgentRunRequest request) {
        return runLifecycle.execute(request, this::executeAgentRequest);
    }

    /** Executes until completion or the first durable InterpretationPlan boundary. */
    @Override
    public AgentRunExecutionSlice executeUntilPlanSuspension(
        AgentRunRequest request, KernelDataScope scope) {
        return planExecutionBridge.executeUntilSuspension(request, scope, this::execute);
    }

    @Override
    public AgentRunExecutionSlice resumeAfterPlanExecution(
        AgentPlanPipelineContinuation continuation,
        InterpretationPlanRuntime.ExecutionResult executionResult,
        KernelDataScope scope
    ) {
        return planExecutionBridge.resume(continuation, executionResult, scope, this::execute);
    }

    @Override
    public PlanModelArbitrationResult arbitrate(PlanModelArbitrationCommand command) {
        return planPhaseActivities.arbitrate(command);
    }

    @Override
    public PlanStepPreparationResult prepare(PlanStepPreparationCommand command) {
        return planPhaseActivities.prepare(command);
    }

    @Override
    public PreparedPlanStep finalizeStep(PlanStepFinalizationCommand command) {
        return planPhaseActivities.finalizeStep(command);
    }

    @Override
    public PlanNodePersistenceResult persist(PlanNodePersistenceCommand command) {
        return planPhaseActivities.persist(command);
    }

    private AgentPlanPhaseActivityCoordinator.Operations phaseActivityOperations() {
        return new AgentPlanPhaseActivityCoordinator.Operations() {
            public ChatModel model(String name) { return chatModelResolver.resolveChatModel(name); }
            public InterpretationPlanRuntime.DagDecision decide(ChatModel model, String query,
                String prompt, InterpretationPlanRuntime.DagDecisionRequest request) {
                return decideInterpretationPlanDagStep(model, query, prompt, () -> false, request);
            }
            public InterpretationPlanRuntime.StepReview review(ChatModel model, String query,
                String prompt, InterpretationPlanRuntime.StepReviewRequest request) {
                return reviewInterpretationPlanToolResult(model, query, prompt, () -> false, request);
            }
            public Map<String, Object> enrich(ChatModel model, String query,
                InterpretationPlanRuntime.StepInputEnrichmentRequest request) {
                String tool = request.step() == null ? null : request.step().toolName();
                var evidence = templateRetrievalEvidenceContext(query, request.completed());
                Map<String, Object> input = modelAssistedContextParameterBridge.propose(
                    model, tool, request.input(), evidence);
                return modelAssistedRetrievalBridge.enrichWithGate(
                    model, tool, input, evidence).argumentsWithGateMarker();
            }
            public PlanToolExecutionPort toolPort() { return planToolExecutionPort; }
        };
    }

    private AgentOrchestrator.AgentExecutionResult executeAgentRequest(AgentRunRequest request) {
        return planExecutionBridge.withRequest(request, () -> executeAgent(
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
            ));
    }



    /** Installs the Runtime OS protocol suite without coupling orchestration to implementations. */
    @Autowired(required = false)
    @SuppressWarnings("unchecked")
    public void setRuntimeProtocolRegistry(RuntimeProtocolRegistry registry) {
        if (registry == null) return;
        this.evidenceGovernanceBridge = registry.require(RuntimeResultAnalysisProtocol.class);
        this.mcpAnalysisContextAdapter = registry.require(RuntimeAnalysisContextProtocol.class);
        this.analysisSummaryGovernanceBridge =
            (DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope>)
                (DataAnalysisSummaryProtocol<?, ?>)
                    registry.require(DataAnalysisSummaryProtocol.class);
        this.summaryGovernanceCoordinator.setProtocol(this.analysisSummaryGovernanceBridge);
        this.analysisDatasetWorker.setSummaryProtocol(this.analysisSummaryGovernanceBridge);
        this.analysisTaskDispatcher =
            (ModelSummaryDispatcher<AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult>)
                (ModelSummaryDispatcher<?, ?, ?>) registry.require(ModelSummaryDispatcher.class);
        this.analysisDispatchCoordinator.setSummaryProtocol(this.analysisSummaryGovernanceBridge);
        this.analysisDispatchCoordinator.setDispatcher(this.analysisTaskDispatcher);
        this.hierarchicalAnalysisReducer =
            (ModelSummaryReducer<AnalysisSummaryResult, HierarchicalAnalysisReducer.Context,
                HierarchicalAnalysisReducer.Result>) (ModelSummaryReducer<?, ?, ?>)
                    registry.require(ModelSummaryReducer.class);
        this.analysisSynthesisCoordinator.setHierarchicalReducer(this.hierarchicalAnalysisReducer);
        this.analysisEvidenceCoordinator.setProtocols(
            this.mcpAnalysisContextAdapter, this.evidenceGovernanceBridge);
        this.toolObservationBuilder = new ToolObservationBuilder(
            this.evidenceTrustEvaluator, this.evidenceGovernanceBridge);
        this.planExecutionObservationCoordinator.setObservationBuilder(this.toolObservationBuilder);
        this.toolExecutor.setObservationBuilder(this.toolObservationBuilder);
        this.mandatoryWorkflowResultReviewer.setObservationBuilder(this.toolObservationBuilder);
        this.answerFinalizer.setAnalysisSummaryProtocol(this.analysisSummaryGovernanceBridge);
    }


    private Map<String, Object> runtimeAttributesFor(AgentRunRequest request) {
        return runtimeAttributeCompiler.compile(request);
    }

    private void pinDagGovernanceContract(Map<String, Object> attributes) {
        runtimeAttributeCompiler.pinContract(attributes);
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
    public AgentOrchestrator.AgentExecutionResult executeAgent(String query,
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
    public AgentOrchestrator.AgentExecutionResult executeAgent(String query,
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
        // A published business child is the model-facing implementation. Its
        // abstract parent remains registered for transport delegation and for the
        // no-child fallback, but exposing both to the planner creates duplicate
        // discovery/review nodes for one logical capability.
        List<String> plannerVisibleTools = toolNames.plannerVisibleTools(tools);
        Map<String, List<String>> plannerInternalDelegations =
            toolNames.plannerInternalDelegations(tools);
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
        Map<String, Object> modelUsage = Collections.synchronizedMap(new LinkedHashMap<>());
        activeChatModel = new MeteredChatModel(
            activeChatModel,
            modelUsage,
            runtimeGuard.runtimeLong(requestRuntimeAttributes.get("__agentTokenBudget"),
                agentRuntimeProperties.modelTokenBudget()),
            runtimeDouble(requestRuntimeAttributes.get("__agentCostBudget"),
                agentRuntimeProperties.modelCostBudget()),
            agentRuntimeProperties.modelInputCostPerThousandTokens(),
            agentRuntimeProperties.modelOutputCostPerThousandTokens(),
            agentRuntimeProperties.budgetAlertRatio()
        );
        List<InteractionToolTrace> traces = new ArrayList<>();
        List<String> observations = runResultAdapter.runtimeObservationList(stringValue(requestRuntimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Map<String, Object>> plannerSteps = new ArrayList<>();
        metadata.put("agentRunId", stringValue(requestRuntimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE)));
        metadata.put("executionId", stringValue(requestRuntimeAttributes.get("__executionId")));
        metadata.put("rootExecutionId", stringValue(requestRuntimeAttributes.get("__rootExecutionId")));
        metadata.put("executionAttemptId", stringValue(requestRuntimeAttributes.get("__executionAttemptId")));
        metadata.put("parentAttemptId", stringValue(requestRuntimeAttributes.get("__parentAttemptId")));
        metadata.put("executionAttemptNumber", requestRuntimeAttributes.get("__executionAttemptNumber"));
        metadata.put("requestId", requestId);
        metadata.put("conversationId", conversationId);
        metadata.put("tenantId", tenantId);
        metadata.put("userId", userId);
        metadata.put("skillId", skillId == null ? "general" : skillId);
        metadata.put("modelName", normalizeModelName(modelName));
        metadata.put("modelUsage", modelUsage);
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

        AgentPlanPipelineContinuation continuation = planExecutionBridge.resumedPipeline();
        if (continuation != null) {
            return executeInterpretationPlanPipeline(
                continuation.currentPlan(),
                activeChatModel,
                query,
                systemPrompt,
                tenantId,
                requestId,
                conversationId,
                userId,
                tools,
                new LinkedHashMap<>(continuation.runtimeAttributes()),
                new ArrayList<>(continuation.traces()),
                new ArrayList<>(continuation.observations()),
                new LinkedHashMap<>(continuation.metadata()),
                documentIds,
                documentTags,
                webSearchResultLimit,
                maxToolCalls,
                cancellationCheck
            );
        }

        log.info("[{}] Agent orchestration started. tools={}", requestId, tools.size());
        log.info("[{}] Agent planner capability projection. plannerVisibleTools={} internalToolDelegations={}",
            requestId, plannerVisibleTools, plannerInternalDelegations);
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
                "plannerVisibleTools", plannerVisibleTools,
                "internalToolDelegations", plannerInternalDelegations,
                "mandatoryTools", mandatoryTools,
                "documentSearchTool", documentSearchTool,
                "verificationWebSearchTool", verificationWebSearchTool
            )
        );

        Set<String> completedWorkflowTools = new LinkedHashSet<>();
        AgentOrchestrator.ToolCallExecution pendingConfirmedExecution = executePendingConfirmedTool(
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
            PlannerExecutionResult plannerResult = planner.plan(new AgentPlanningRequest(
                activeChatModel, query, systemPrompt, plannerVisibleTools, observations, documentIds,
                documentTags, plannerMandatoryTools, plannerRequiresToolBeforeFinal,
                requireDocumentWebVerification, documentSearchTool,
                verificationWebSearchTool, RuntimeFunctionCallingPolicy.planningAttributes(requestRuntimeAttributes, plannerVisibleTools, mandatoryTools, plannerCompletedTools, authoritativeWorkflowDag, requireDocumentWebVerification, workflowTools, toolRegistry)));
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
            planEvolutionAuditor.recordPlannerRepair(
                requestRuntimeAttributes, metadata,
                decision.executionPlan() == null ? null : decision.executionPlan().get("repairEvent"));
            recordLifecyclePhase(
                requestRuntimeAttributes,
                metadata,
                "plan_generation",
                AgentLifecyclePresentationPolicy.planGenerationContent(decision),
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
            AgentOrchestrator.ToolCallExecution execution = executeToolCall(
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
            AgentOrchestrator.ToolCallExecution execution = executeToolCall(
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

        AgentOrchestrator.AgentExecutionResult blockedResult = finishMandatoryWorkflowBlockedIfPending(
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

    private AgentOrchestrator.AgentExecutionResult executeInterpretationPlanPipeline(InterpretationPlan plan,
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
        metadata.put("runtimePlanLatencyBudgetMs", budgetCaps.latencyBudgetMs());
        metadata.put("effectivePlanLatencyBudgetMs",
            plan.executionPolicy() == null ? null : plan.executionPolicy().latencyBudgetMs());
        metadata.put("modelPlanLatencyBudgetEnforced", false);
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
        planSnapshotService.saveGenerated(
            "initial", plan, tenantId, requestId, runtimeAttributes, metadata);
        planEvolutionAuditor.recordEvolution(
            null, plan, 1, "INITIAL", List.of(), runtimeAttributes, metadata);

        InterpretationPlanValidator validator = new InterpretationPlanValidator();
        Map<String, Object> pipelineRuntimeAttributes = runtimeAttributes;
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            validator,
            new InterpretationPlanOptimizer(toolRegistry),
            runStore,
            request -> reviewInterpretationPlanToolResult(activeChatModel, query, systemPrompt,
                cancellationCheck, request, pipelineRuntimeAttributes),
            request -> decideInterpretationPlanDagStep(activeChatModel, query, systemPrompt,
                cancellationCheck, request, pipelineRuntimeAttributes),
            request -> {
                String stepTool = request.step() == null ? null : request.step().toolName();
                ModelAssistedRetrievalBridge.RetrievalEvidenceContext evidenceContext =
                    templateRetrievalEvidenceContext(query, request.completed());
                Map<String, Object> contextual = modelAssistedContextParameterBridge.propose(
                    activeChatModel, stepTool, request.input(), evidenceContext);
                return modelAssistedRetrievalBridge.enrichWithGate(
                    activeChatModel, stepTool, contextual, evidenceContext).argumentsWithGateMarker();
            },
            planToolExecutionPort,
            planDagControlPort
        );
        runtime.setNodeAttemptStore(nodeAttemptStore);
        AgentPlanPipelineContinuation resumedPipeline = planExecutionBridge.resumedPipeline();
        InterpretationPlan initialPipelinePlan = resumedPipeline == null
            ? plan : resumedPipeline.initialPlan();
        int resumedRewriteCount = resumedPipeline == null ? 0 : resumedPipeline.rewriteCount();
        List<InterpretationPlanRuntime.ExecutionResult> planAttemptResults = new ArrayList<>(
            resumedPipeline == null ? List.of() : resumedPipeline.attemptResults());
        List<Map<String, Object>> evidenceHistory = new ArrayList<>(
            resumedPipeline == null ? List.of() : resumedPipeline.evidenceHistory());
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
            InterpretationPlanRuntime.ExecutionResult resumedResult = planExecutionBridge.consume(
                plan, ToolCallFingerprint.forPlan(plan));
            if (resumedResult != null) {
                firstResult = resumedResult;
            } else {
                suspendForDurablePlanExecution(
                    initialPipelinePlan, plan, executionRequest,
                    resumedRewriteCount, maxRewriteTimes(initialPipelinePlan),
                    planAttemptResults, evidenceHistory,
                    runtimeAttributes, traces, observations, metadata);
                firstResult = runtime.execute(executionRequest,
                    planKernelScope(tenantId, userId, requestId, conversationId, runtimeAttributes));
            }
        } else {
            firstResult = planEvaluationFailure("initial", initialEvaluation);
        }
        recordPlanRuntimeResult("initial", firstResult, traces, observations, metadata);
        planSnapshotService.saveExecution(
            "initial_result", plan, tenantId, requestId, runtimeAttributes, metadata, firstResult);
        checkCancelledUnlessBatchEvidence(cancellationCheck, firstResult, metadata);

        if (firstResult.approvalRequired()) {
            metadata.put("stopReason", "confirmation_required");
            metadata.put("confirmationRequired", true);
            return answerFinalizer.finishExecution("", traces, metadata, observations);
        }
        firstResult = consumePlanExecutionResult(
            "initial", plan, firstResult, runtimeAttributes, observations, metadata);
        planAttemptResults.add(firstResult);
        Map<String, Object> firstEvidence = analyzeInterpretationPlanEvidence(
            activeChatModel, query, systemPrompt, plan, firstResult, 1, evidenceHistory,
            runtimeAttributes, metadata, cancellationCheck
        );
        RecordCoverageBundle latestRecordCoverage = analyzeClaimAdmissionCoverage(
            activeChatModel, query, firstResult, planAttemptResults, runtimeAttributes, metadata,
            cancellationCheck);
        firstEvidence = semanticClaimCoordinator.evaluate(firstEvidence,
            latestRecordCoverage.summaryResults(), 1, runtimeAttributes, metadata);
        evidenceHistory.add(firstEvidence);
        int configuredMaxRewriteTimes = maxRewriteTimes(initialPipelinePlan);
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
                "initial",
                latestRecordCoverage
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
            analysisRefinementCoordinator.reusableSteps(Map.of(), currentPlan, currentResult);
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
        for (int rewriteCount = resumedRewriteCount + 1;
             rewriteCount <= maxRewriteTimes; rewriteCount++) {
            String rewriteSummary = planEvolutionAuditor.rewriteSummary(
                rewriteCount,
                currentPlan,
                currentResult,
                evidenceHistory
            );
            observations.add(rewriteSummary);
            InterpretationPlan.Step failedStep = analysisRefinementCoordinator.repairRootStep(
                currentPlan, currentResult);
            String repairReason = analysisRefinementCoordinator.rewriteReason(
                currentResult, evidenceHistory);
            Map<String, Object> repairEvidenceContext = planEvolutionAuditor.repairContext(evidenceHistory);
            metadata.put("latestDagRepairEvidenceContext", repairEvidenceContext);
            boolean dagRepairAttempt = !currentResult.success() || failedStep != null;
            if (dagRepairAttempt) {
                planEvolutionAuditor.recordDagRepair(
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
                    : null,
                AgentRoleAnalysisContext.fromRuntimeAttributes(runtimeAttributes)
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
            planEvolutionAuditor.recordEvolution(
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
                : planEvolutionAuditor.changes(currentPlan, rewrittenPlan);
            if (dagRepairAttempt) {
                planEvolutionAuditor.recordDagRepair(
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
            planSnapshotService.saveGenerated(
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
            suspendForDurablePlanExecution(
                initialPipelinePlan, currentPlan, rewriteRequest,
                rewriteCount, maxRewriteTimes, planAttemptResults, evidenceHistory,
                runtimeAttributes, traces, observations, metadata);
            currentResult = runtime.execute(rewriteRequest,
                planKernelScope(tenantId, userId, requestId, conversationId, rewriteExecutionAttributes));
            reusablePlanSteps = analysisRefinementCoordinator.reusableSteps(
                reusablePlanSteps, currentPlan, currentResult);
            recordPlanRuntimeResult(rewriteStage, currentResult, traces, observations, metadata);
            planSnapshotService.saveExecution(
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
            currentResult = consumePlanExecutionResult(
                rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                currentPlan,
                currentResult,
                runtimeAttributes,
                observations,
                metadata
            );
            planAttemptResults.add(currentResult);
            Map<String, Object> currentEvidence = analyzeInterpretationPlanEvidence(
                activeChatModel, query, systemPrompt, currentPlan, currentResult, rewriteCount + 1,
                evidenceHistory, runtimeAttributes, metadata, cancellationCheck
            );
            latestRecordCoverage = analyzeClaimAdmissionCoverage(
                activeChatModel, query, currentResult, planAttemptResults, runtimeAttributes, metadata,
                cancellationCheck);
            currentEvidence = semanticClaimCoordinator.evaluate(currentEvidence,
                latestRecordCoverage.summaryResults(), rewriteCount + 1, runtimeAttributes, metadata);
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
                    rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                    latestRecordCoverage
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
        metadata.put("interpretationPlanFallbackMode",
            planEvolutionAuditor.fallbackMode(initialPipelinePlan));
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
        AgentOrchestrator.AgentExecutionResult blockedResult = finishMandatoryWorkflowBlockedIfPending(
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
        AgentOrchestrator.AgentExecutionResult planWorkflowBlockedResult = finishInterpretationPlanWorkflowBlockedIfPending(
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
            planAttemptResults.addAll(RecoveredBatchEvidenceBridge.project(traces, objectMapper));
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
                synthesisStage,
                latestRecordCoverage
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

    protected AgentOrchestrator.AgentExecutionResult finishInterpretationPlanWorkflowBlockedIfPending(
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

    private AgentOrchestrator.AgentExecutionResult finishSynthesizedInterpretationPlanAnswer(
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

    private AgentOrchestrator.AgentExecutionResult finishMandatoryWorkflowBlockedIfPending(ChatModel activeChatModel,
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
            String contractFailure = "必需数据获取步骤未执行：模板发现结果没有提供兼容的运行时合同。"
                + " 已完成的数据证据均已保留；请检查数据能力配置或维护匹配的模板。";
            return answerFinalizer.finishExecution(contractFailure, traces, metadata, observations);
        }
        List<String> failureParts = new ArrayList<>();
        if (!failedMandatoryTools.isEmpty()) {
            failureParts.add("必需数据获取步骤已执行但失败");
        }
        if (!pendingMandatoryTools.isEmpty()) {
            failureParts.add("必需数据获取步骤正在等待确认");
        }
        if (!unattemptedMandatoryTools.isEmpty()) {
            failureParts.add("必需数据获取步骤尚未执行；前置节点失败或覆盖校验未通过");
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

    private void suspendForDurablePlanExecution(
        InterpretationPlan initialPlan,
        InterpretationPlan currentPlan,
        InterpretationPlanRuntime.ExecutionRequest executionRequest,
        int rewriteCount,
        int maxRewriteTimes,
        List<InterpretationPlanRuntime.ExecutionResult> attemptResults,
        List<Map<String, Object>> evidenceHistory,
        Map<String, Object> runtimeAttributes,
        List<InteractionToolTrace> traces,
        List<String> observations,
        Map<String, Object> metadata
    ) {
        planExecutionBridge.suspend(
            initialPlan, currentPlan, executionRequest, rewriteCount, maxRewriteTimes,
            attemptResults, evidenceHistory, runtimeAttributes, traces, observations, metadata,
            ToolCallFingerprint.forPlan(currentPlan));
    }

    protected Map<String, Object> workflowAttemptAttributes(Map<String, Object> runtimeAttributes, int attempt) {
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
        return decideInterpretationPlanDagStep(
            activeChatModel, query, systemPrompt, cancellationCheck, request, Map.of());
    }

    private InterpretationPlanRuntime.DagDecision decideInterpretationPlanDagStep(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        BooleanSupplier cancellationCheck,
        InterpretationPlanRuntime.DagDecisionRequest request,
        Map<String, Object> runtimeAttributes
    ) {
        runtimeGuard.checkCancelled(cancellationCheck);
        if (activeChatModel == null || request == null) {
            return InterpretationPlanRuntime.DagDecision.abort("LLM DAG controller is unavailable.");
        }
        String prompt = buildInterpretationPlanDagDecisionPrompt(
            query, systemPrompt, request, runtimeAttributes);
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
        return buildInterpretationPlanDagDecisionPrompt(query, systemPrompt, request, Map.of());
    }

    String buildInterpretationPlanDagDecisionPrompt(String query,
                                                    String systemPrompt,
                                                    InterpretationPlanRuntime.DagDecisionRequest request,
                                                    Map<String, Object> runtimeAttributes) {
        ContextTokenEstimator.Size evidenceSize = estimateDagDecisionEvidenceSize(request);
        int dagEvidenceTokenBudget = dagDecisionEvidenceTokenBudget();
        boolean compressionEnabled = evidenceSize.tokens() > dagEvidenceTokenBudget;
        String prompt = renderInterpretationPlanDagDecisionPrompt(
            query,
            systemPrompt,
            request,
            compressionEnabled,
            evidenceSize,
            runtimeAttributes
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
        ContextTokenEstimator.Size evidenceSize,
        Map<String, Object> runtimeAttributes
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("System policy inheritance: the validated plan already carries the user intent, scope, "
            + "constraints, and approved tools. This controller may narrow execution but must not expand that scope.\n\n");
        prompt.append("You are the responsible Agent Runtime DAG execution controller.\n");
        String roleContext = AgentRoleAnalysisContext.promptSectionFromRuntime(
            runtimeAttributes, "DAG_EXECUTION_AND_SEMANTIC_ARBITRATION");
        if (!roleContext.isEmpty()) prompt.append(roleContext).append('\n');
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
        prompt.append("For template discovery, the same JSON object must additionally contain "
            + "\"analysis_intent\":{\"business_goal\":\"...\",\"analysis_subject\":\"...\","
            + "\"core_entities\":[],\"metrics\":[],\"dimensions\":[],\"analysis_focus\":[],"
            + "\"time_scope\":\"...\",\"expected_relationships\":[]}, and "
            + "\"template_relationships\":[{\"from_template_id\":\"...\",\"to_template_id\":\"...\","
            + "\"relation_type\":\"...\",\"description\":\"...\"}]. Every template_evaluations item "
            + "must also contain analysis_role and relevance_level.\n");
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

    private RecordCoverageBundle analyzeClaimAdmissionCoverage(ChatModel activeChatModel, String query,
        InterpretationPlanRuntime.ExecutionResult latest, List<InterpretationPlanRuntime.ExecutionResult> attempts,
        Map<String, Object> runtimeAttributes, Map<String, Object> metadata, BooleanSupplier cancellationCheck) {
        if (activeChatModel == null || latest == null) return RecordCoverageBundle.empty();
        return semanticClaimCoordinator.preflight(
            () -> buildRecordCoverageBundle(activeChatModel, query, cumulativeEvidenceResult(latest, attempts),
                runtimeAttributes, metadata, cancellationCheck),
            RecordCoverageBundle::empty, runtimeAttributes, metadata);
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

    private String synthesizeInterpretationPlanAnswer(ChatModel activeChatModel, String query, String systemPrompt,
        InterpretationPlanRuntime.ExecutionResult result, List<InterpretationPlanRuntime.ExecutionResult> attemptResults,
        Map<String, Object> runtimeAttributes, List<String> observations, Map<String, Object> metadata,
        BooleanSupplier cancellationCheck, String stage, RecordCoverageBundle precomputedRecordCoverage) {
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
        RecordCoverageBundle recordCoverage = precomputedRecordCoverage == null
            ? buildRecordCoverageBundle(activeChatModel, query, cumulativeEvidenceResult,
                runtimeAttributes, metadata, cancellationCheck)
            : precomputedRecordCoverage;
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
        if (!recordCoverage.promptEvidence().isBlank()
            && cumulativeEvidenceResult.steps().stream().anyMatch(step -> step.metadata() != null
                && step.metadata().containsKey(TemplateMatchAnalysis.ANALYSIS_CONTEXT_KEY))) prompt =
            com.chatchat.agents.orchestration.analysis.prompt.GovernedRecordFinalPromptBuilder.build(
                query, systemPrompt, recordCoverage.promptEvidence());
        String reviewEvidenceContext = interpretationPlanReviewEvidenceContext(prompt);
        if (metadata != null && !reviewEvidenceContext.isBlank()) {
            metadata.put("modelAnalysisReviewContext", reviewEvidenceContext);
            metadata.put("modelEvidenceReviewRewriteAllowed", true);
            metadata.put("modelAnalysisReviewContractVersion", "model_analysis_repair_v1");
        }
        String runId = stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(AGENT_RUN_ID_ATTRIBUTE));
        String finalPrompt = prompt;
        AnalysisSynthesisCoordinator.FinalSynthesisResult synthesis =
            analysisSynthesisCoordinator.synthesizeFinal(
                new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
                    activeChatModel, finalPrompt, stage, firstNonBlank(runId, ""),
                    result == null || result.steps() == null ? 0 : result.steps().size(),
                    attemptResults == null ? 0 : attemptResults.size(), storedObservations.size(),
                    summarizeAvailableResults(runtimeAttributes),
                    () -> ensureCompleteRecordCoveragePresented(
                        buildDeterministicAvailableResultAnswer(cumulativeEvidenceResult),
                        recordCoverage, metadata),
                    candidate -> {
                        String guarded = ensureConcreteBatchEvidencePresented(
                            candidate, query, cumulativeEvidenceResult, metadata);
                        guarded = ensureCompleteRecordCoveragePresented(guarded, recordCoverage, metadata);
                        return removeUnsupportedCurrentTurnDocumentReferences(
                            guarded, cumulativeEvidenceResult, metadata);
                    },
                    result == null ? "" : firstNonBlank(result.finalAnswer(), ""),
                    recordCoverage.returnedRecordCount(), recordCoverage.processedRecordCount(),
                    recordCoverage.coverageComplete(), recordCoverage.evidenceTraceComplete(),
                    recordCoverage.sourceContentComplete(), recordCoverage.iterations(),
                    recordCoverage.rawReplayChunkCount(), recordCoverage.summaryResults(),
                    recordCoverage.synthesisInputs(), runtimeAttributes, metadata));
        if (driverChallengeRepairRequired(metadata)) {
            metadata.put("analysisDriverRepairRound", 1);
            metadata.put("analysisDriverRepairStarted", true);
            metadata.put("analysisDriverRepairBudget", 1);
            metadata.put("analysisDriverRepairBudgetRemaining", 0);
            metadata.put("analysisReuseExistingDataset", true);
            metadata.put("analysisDataRequeryAllowed", false);
            recordLifecyclePhase(runtimeAttributes, metadata, "driver_challenge_repair",
                "Driver challenge routed to the governed analysis pipeline using retained data.",
                metadataOf("stage", stage, "round", 1, "reuseExistingDataset", true,
                    "dataAcquisitionAllowed", false,
                    "repairRequests", metadata.get("analysisDriverRepairRequests")));
            RecordCoverageBundle repairedCoverage = buildRecordCoverageBundle(
                activeChatModel, query, cumulativeEvidenceResult,
                runtimeAttributes, metadata, cancellationCheck);
            String repairedPrompt = com.chatchat.agents.orchestration.analysis.prompt
                .GovernedRecordFinalPromptBuilder.build(
                    query, systemPrompt, repairedCoverage.promptEvidence());
            synthesis = analysisSynthesisCoordinator.synthesizeFinal(
                new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
                    activeChatModel, repairedPrompt, stage, firstNonBlank(runId, ""),
                    result == null || result.steps() == null ? 0 : result.steps().size(),
                    attemptResults == null ? 0 : attemptResults.size(), storedObservations.size(),
                    summarizeAvailableResults(runtimeAttributes),
                    () -> ensureCompleteRecordCoveragePresented(
                        buildDeterministicAvailableResultAnswer(cumulativeEvidenceResult),
                        repairedCoverage, metadata),
                    candidate -> {
                        String guarded = ensureConcreteBatchEvidencePresented(
                            candidate, query, cumulativeEvidenceResult, metadata);
                        guarded = ensureCompleteRecordCoveragePresented(
                            guarded, repairedCoverage, metadata);
                        return removeUnsupportedCurrentTurnDocumentReferences(
                            guarded, cumulativeEvidenceResult, metadata);
                    },
                    result == null ? "" : firstNonBlank(result.finalAnswer(), ""),
                    repairedCoverage.returnedRecordCount(), repairedCoverage.processedRecordCount(),
                    repairedCoverage.coverageComplete(), repairedCoverage.evidenceTraceComplete(),
                    repairedCoverage.sourceContentComplete(), repairedCoverage.iterations(),
                    repairedCoverage.rawReplayChunkCount(), repairedCoverage.summaryResults(),
                    repairedCoverage.synthesisInputs(), runtimeAttributes, metadata));
            metadata.put("analysisDriverRepairFinished", true);
        }
        return synthesis.content();
    }

    private boolean driverChallengeRepairRequired(Map<String, Object> metadata) {
        if (metadata == null || metadata.containsKey("analysisDriverRepairRound")) return false;
        Object repairs = metadata.get("analysisDriverRepairRequests");
        return Boolean.TRUE.equals(metadata.get("analysisRepairRequired"))
            && repairs instanceof java.util.Collection<?> values && !values.isEmpty()
            && Boolean.TRUE.equals(metadata.get("analysisReuseExistingDataset"))
            && Boolean.FALSE.equals(metadata.get("analysisDataRequeryAllowed"));
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
                Object resolved = analysisEvidenceCoordinator.resolveEvidenceData(step);
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

    /** Builds the evidence view used by deterministic final-answer guards. Incremental DAG repair
     * may move an executed batch into an earlier attempt while the latest attempt only contains
     * repaired downstream steps. Prompt synthesis already sees every attempt; the immutable
     * record-coverage and concrete-value guards must see the same cumulative evidence chain. */
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
        if (start < 0) start = synthesisPrompt.indexOf(
            "Governed dataset analysis and coverage contract:");
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
                    Map<String, Object> outputFacts = toolResultFactExtractor.structuredOutputFacts(step.output());
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
                        String serializedOutput = stringify(toolResultFactExtractor.redactExecutionStatements(step.output()));
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
        Map<String, Object> facts = toolResultFactExtractor.structuredOutputFacts(output);
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
        AnalysisCoverageCoordinator.CoverageBundle coverage = analysisCoverageCoordinator.analyze(
            new AnalysisCoverageCoordinator.Request(
                activeChatModel, query, result, runtimeAttributes, metadata, cancellationCheck,
                () -> runtimeGuard.checkCancelled(cancellationCheck),
                governanceIsolationScope(metadata, runtimeAttributes),
                analysisSummaryGovernanceBridge));
        return new RecordCoverageBundle(
            coverage.promptEvidence(), coverage.appendix(), coverage.recordValueGroups(),
            coverage.returnedRecordCount(), coverage.processedRecordCount(), coverage.iterations(),
            coverage.iterative(), coverage.coverageComplete(), coverage.sourceContentComplete(),
            coverage.evidenceTraceComplete(), coverage.rawReplayChunkCount(),
            coverage.summaryResults(), coverage.synthesisInputs());
    }

    /** Executes a durable dataset Activity by resolving all process-local dependencies afresh. */
    @Override
    public AnalysisTaskResult execute(
        AnalysisTask task,
        ModelSummaryProgressReporter progressReporter
    ) {
        return analysisDatasetActivityExecutor.execute(task, progressReporter);
    }

    String ensureCompleteRecordCoveragePresented(
        String answer,
        RecordCoverageBundle coverage,
        Map<String, Object> metadata
    ) {
        return analysisSynthesisCoordinator.presentGovernedAnalysis(answer,
            new AnalysisSynthesisCoordinator.PresentationRequest(
                coverage.appendix(), coverage.recordValueGroups(), coverage.returnedRecordCount(),
                coverage.iterative(), coverage.coverageComplete(), coverage.sourceContentComplete(),
                coverage.evidenceTraceComplete(), coverage.summaryResults(), coverage.synthesisInputs(),
                metadata));
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
        Map<String, Object> outputFacts = toolResultFactExtractor.structuredOutputFacts(execution.output());
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
        return reviewInterpretationPlanToolResult(
            activeChatModel, query, systemPrompt, cancellationCheck, request, Map.of());
    }

    private InterpretationPlanRuntime.StepReview reviewInterpretationPlanToolResult(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        BooleanSupplier cancellationCheck,
        InterpretationPlanRuntime.StepReviewRequest request,
        Map<String, Object> runtimeAttributes
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
        String raw = activeChatModel.chat(buildToolResultReviewPrompt(
            query, systemPrompt, request, runtimeAttributes));
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
        Map<String, Object> businessAnalysisIntent = asMap(firstObject(payload,
            "analysis_intent", "analysisIntent", "business_analysis_intent", "businessAnalysisIntent"));
        if (!businessAnalysisIntent.isEmpty()) {
            metadata.put("businessAnalysisIntent", businessAnalysisIntent);
        }
        Object templateRelationships = firstObject(payload,
            "template_relationships", "templateRelationships", "expected_template_relationships");
        if (templateRelationships instanceof Iterable<?>) {
            metadata.put("templateRelationships", templateRelationships);
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

    protected String buildToolResultReviewPrompt(String query,
                                               String systemPrompt,
                                               InterpretationPlanRuntime.StepReviewRequest request) {
        return buildToolResultReviewPrompt(query, systemPrompt, request, Map.of());
    }

    protected String buildToolResultReviewPrompt(String query,
                                               String systemPrompt,
                                               InterpretationPlanRuntime.StepReviewRequest request,
                                               Map<String, Object> runtimeAttributes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("System policy inheritance: the validated step already carries the user intent, scope, "
            + "constraints, and approved tool. Review only this execution and do not expand that scope.\n\n");
        prompt.append("You are the runtime reviewer for one completed MCP tool call.\n");
        String roleContext = AgentRoleAnalysisContext.promptSectionFromRuntime(
            runtimeAttributes, "TOOL_RESULT_REVIEW_AND_TEMPLATE_SELECTION");
        if (!roleContext.isEmpty()) prompt.append(roleContext).append('\n');
        prompt.append("Return strict JSON only with this shape:\n");
        prompt.append("{\"satisfied\":true|false,\"iteration_sufficient\":true|false,\"reason\":\"short reason\",\"review_answer\":\"optional audit note, not user-facing final answer\",\"evidence_used\":[{\"basis\":\"returned fact\"}],\"missing_evidence\":[\"material gap\"],\"conflicts\":[\"conflict\"],\"hypotheses\":[{\"hypothesis_id\":\"H1\",\"parent_hypothesis_id\":null,\"statement\":\"testable explanation\",\"support_evidence_ids\":[],\"contradict_evidence_ids\":[],\"confidence\":0.0,\"status\":\"SUPPORTED|CONTRADICTED|UNRESOLVED\"}],\"next_actions\":[{\"tool\":\"available_tool_name\",\"intent\":\"evidence gap to close or hypothesis to test\",\"input_changes\":{\"parameter\":\"revised value\"},\"reason\":\"why this action is needed\",\"based_on\":[\"evidenceId\",\"hypothesisId\"],\"scope_basis\":{\"source\":\"user_query|tool_result\",\"reference\":\"exact user quote or returned JSON path\"},\"capability_basis\":{\"source\":\"tool_result|tool_metadata\",\"reference\":\"returned capability JSON path or declared tool capability\"},\"expected_evidence_types\":[\"specific evidence type\"]}],\"selected_urls\":[\"https://...\"],\"useful_refs\":[\"doc://...#chunk=0\"],\"rejected_refs\":[\"doc://...#chunk=1\"],\"selected_asset_ids\":[\"asset-id\"],\"rejected_asset_ids\":[\"asset-id\"],\"asset_evaluations\":[{\"asset_id\":\"asset-id\",\"relevance\":0.0,\"decision\":\"accept|reject\",\"reasons\":[\"evidence-based reason\"]}],\"selected_template_ids\":[\"template-id\"],\"rejected_template_ids\":[\"template-id\"],\"analysis_intent\":{\"business_goal\":\"goal\",\"analysis_subject\":\"subject\",\"core_entities\":[],\"metrics\":[],\"dimensions\":[],\"analysis_focus\":[],\"time_scope\":\"scope\",\"expected_relationships\":[]},\"template_relationships\":[{\"from_template_id\":\"template-id\",\"to_template_id\":\"template-id\",\"relation_type\":\"business relation\",\"description\":\"evidence-based description\"}],\"template_evaluations\":[{\"template_id\":\"template-id\",\"business_group\":\"returned group\",\"relevance\":0.0,\"relevance_level\":\"HIGH|MEDIUM|LOW\",\"evidence_fit\":0.0,\"parameter_readiness\":0.0,\"total_score\":0.0,\"decision\":\"accept|reject\",\"analysis_role\":\"TARGET|CAUSE|CONTEXT|DIMENSION|VALIDATION|EXPLANATION|IRRELEVANT\",\"reasons\":[\"evidence-based reason\"],\"missing_parameters\":[],\"matched_question_aspects\":[\"question aspect\"],\"relationship_hints\":[\"declared relationship to another selected dataset\"]}],\"template_execution_satisfied\":true|false,\"missing_parameters\":[\"parameter\"],\"retry_input_changes\":{\"parameters\":{\"parameter\":\"value proven by user/tool evidence\"}},\"reselect_template\":true|false,\"refined_intent\":\"optional refined retrieval intent\",\"relevance\":0.0,\"answerability\":0.0,\"supportsQuestionAspect\":[\"process\"],\"missingAspects\":[\"constraints\"],\"usefulness\":\"HIGH|MEDIUM|LOW\",\"shouldExpandQuery\":true|false,\"confidence\":0.0}\n");
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
        prompt.append("- Candidate discovery is high recall, not an execution list. A business group may contain many templates; select only templates materially required by the original user question and the actual cumulative analysis context. Runtime executes the selected_template_ids admission set and must not execute rejected or merely co-grouped candidates.\n");
        prompt.append("- For template discovery, also return analysis_intent with business_goal, analysis_subject, core_entities, metrics, dimensions, analysis_focus, time_scope, and expected_relationships. This is the business data requirement derived jointly from the original question and actual context.\n");
        prompt.append("- Assign every candidate exactly one analysis_role: TARGET, CAUSE, CONTEXT, DIMENSION, VALIDATION, EXPLANATION, or IRRELEVANT. Return template_relationships with from_template_id, to_template_id, relation_type, and description only when supported by the question or declared template metadata.\n");
        prompt.append("- Put unrelated or materially weaker candidates in rejected_template_ids. Do not select a template merely because Lucene ranked it first or its score ties another candidate.\n");
        prompt.append("- For each returned template candidate, emit template_evaluations with business_group, evidence-based relevance, evidence_fit, parameter_readiness, total_score, decision, reasons, missing_parameters, matched_question_aspects, and relationship_hints. Scores are 0..1. Justify selection jointly from the original question, actual cumulative analysis context, and returned capability/schema/dependency metadata. Never infer a relationship absent from those sources.\n");
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
        Map<String, Object> cumulativeContext = templateRequirementReviewContext(request);
        if (!cumulativeContext.isEmpty()) {
            prompt.append("Actual cumulative analysis context (authoritative prior tool evidence and declared semantics; use together with the original question for template admission):\n")
                .append(stringify(cumulativeContext))
                .append("\n\n");
        }
        Map<String, Object> factMetadata = toolResultFactExtractor.executionMetadata(request.execution());
        if (!factMetadata.isEmpty()) {
            prompt.append("Runtime deterministic fact check:\n")
                .append(shortObservationText(stringify(factMetadata), 2500))
                .append("\n\n");
        }
        Map<String, Object> outputFacts = toolResultFactExtractor.structuredOutputFacts(request.execution().output());
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
            String serializedOutput = stringify(
                toolResultFactExtractor.redactExecutionStatements(request.execution().output()));
            prompt.append("Complete tool result:\n")
                .append(serializedOutput)
                .append("\nRuntime truncation applied: false");
        }
        return prompt.toString();
    }

    private Map<String, Object> templateRequirementReviewContext(
        InterpretationPlanRuntime.StepReviewRequest request
    ) {
        if (request == null) return Map.of();
        List<Map<String, Object>> completedEvidence = new ArrayList<>();
        if (request.completed() != null) {
            request.completed().values().stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(
                    item -> item.stepId() == null ? 0 : item.stepId()))
                .limit(12)
                .forEach(execution -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    if (execution.stepId() != null) item.put("stepId", execution.stepId());
                    item.put("toolName", firstNonBlank(execution.toolName(), execution.actionType()));
                    item.put("success", execution.success());
                    Map<String, Object> facts =
                        toolResultFactExtractor.structuredOutputFacts(execution.output());
                    if (!facts.isEmpty()) item.put("returnedFacts", facts);
                    Map<String, Object> semanticContext = mcpAnalysisContextAdapter.adapt(
                        firstNonBlank(execution.toolName(), "completed-step"),
                        toolMetadataOrNull(execution.toolName()), execution.output());
                    if (!semanticContext.isEmpty()) item.put("analysisContext", semanticContext);
                    completedEvidence.add(Map.copyOf(item));
                });
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completedEvidence", List.copyOf(completedEvidence));
        result.put("currentStepInput", request.step() == null || request.step().input() == null
            ? Map.of() : request.step().input());
        result.put("contextRole", "TEMPLATE_REQUIREMENT_ADMISSION_INPUT");
        return Map.copyOf(result);
    }

    private ToolMetadata toolMetadataOrNull(String toolName) {
        return toolName == null || toolName.isBlank()
            ? null : toolRegistry.getToolMetadata(toolName);
    }

    @SuppressWarnings("unchecked")
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

    protected InterpretationPlanRuntime.ExecutionResult rejectUnsatisfiedInterpretationPlanResult(
        String stage,
        InterpretationPlanRuntime.ExecutionResult result,
        List<String> observations,
        Map<String, Object> metadata
    ) {
        return planExecutionResultCoordinator.review(stage, result, observations, metadata);
    }

    private InterpretationPlanRuntime.ExecutionResult consumePlanExecutionResult(
        String stage,
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result,
        Map<String, Object> runtimeAttributes,
        List<String> observations,
        Map<String, Object> metadata
    ) {
        return planExecutionResultCoordinator.consume(new PlanExecutionResultCoordinator.Request(
            stage,
            plan,
            result,
            metadataStringList(metadata, "mandatoryTools"),
            metadataStringList(runtimeAttributes, "workflowCompletedTools"),
            observations,
            metadata
        )).result();
    }

    protected int maxRewriteTimes(InterpretationPlan plan) {
        int runtimeMaximum = MAX_INTERPRETATION_PLAN_ATTEMPTS - 1;
        if (plan == null || plan.executionPolicy() == null
            || plan.executionPolicy().maxRewriteTimes() == null) {
            return runtimeMaximum;
        }
        return Math.max(0, Math.min(runtimeMaximum, plan.executionPolicy().maxRewriteTimes()));
    }

    protected Object authoritativeWorkflowDagForContinuation(Object rawDag,
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

    protected Map<String, Object> analyzeInterpretationPlanEvidence(
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
        return planEvidenceAnalyzer.analyze(
            activeChatModel, query, systemPrompt, plan, result, iteration,
            previousEvidence, runtimeAttributes, metadata, cancellationCheck);
    }


    List<Map<String, Object>> interpretationToolEvidence(
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result,
        int iteration
    ) {
        return planEvidenceAnalyzer.interpretationToolEvidence(plan, result, iteration);
    }

    List<Map<String, Object>> discoveredExecutorActions(
        Object output,
        String sourceTool,
        Integer sourceStepId
    ) {
        return planEvidenceAnalyzer.discoveredExecutorActions(output, sourceTool, sourceStepId);
    }

    List<Object> pendingEvidenceNextActions(List<Map<String, Object>> toolEvidence) {
        return planEvidenceAnalyzer.pendingEvidenceNextActions(toolEvidence);
    }

    private boolean evidenceSufficient(Map<String, Object> snapshot) {
        return analysisLoopCoordinator.sufficient(snapshot);
    }

    private EvidenceAugmentationPolicy.Outcome decideEvidenceAugmentation(
        Map<String, Object> snapshot,
        InterpretationPlanRuntime.ExecutionResult result,
        boolean explorationAvailable,
        boolean authorizationRequired,
        Map<String, Object> metadata
    ) {
        return analysisLoopCoordinator.decide(snapshot, result != null && result.success(),
            explorationAvailable, authorizationRequired, metadata);
    }

    boolean evidenceExplorationAvailable(Map<String, Object> snapshot,
                                         InterpretationPlanRuntime.ExecutionResult result,
                                         List<String> availableTools,
                                         boolean budgetAvailable) {
        return analysisLoopCoordinator.explorationAvailable(snapshot, result != null && result.success(),
            availableTools != null && !availableTools.isEmpty(), budgetAvailable,
            snapshot != null && !evidenceRefinementRequiredTools(List.of(snapshot), availableTools).isEmpty());
    }


    private TaskContract.EvidenceRequirement taskEvidenceRequirement(Map<String, Object> metadata) {
        return analysisLoopCoordinator.evidenceRequirement(metadata);
    }

    private boolean usableEvidenceAvailable(Map<String, Object> snapshot) {
        return analysisLoopCoordinator.usableEvidence(snapshot);
    }

    private void recordEvidenceAugmentationDecision(
        EvidenceAugmentationPolicy.Outcome outcome,
        int iteration,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        analysisLoopCoordinator.recordDecision(outcome, iteration, runtimeAttributes, metadata);
    }

    private void recordEvidenceStopState(
        Map<String, Object> metadata,
        Map<String, Object> snapshot,
        String stopReason,
        int iterations
    ) {
        analysisLoopCoordinator.recordStop(metadata, snapshot, stopReason, iterations);
    }

    private List<InterpretationPlanRewriter.RequiredToolExecution> evidenceRefinementRequiredTools(
        List<Map<String, Object>> evidenceHistory,
        List<String> availableTools
    ) {
        boolean sufficient = evidenceHistory == null || evidenceHistory.isEmpty()
            || evidenceSufficient(evidenceHistory.get(evidenceHistory.size() - 1));
        return analysisRefinementCoordinator.requiredTools(
            evidenceHistory, availableTools, sufficient);
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

    private void recordPlanRuntimeResult(String stage,
                                         InterpretationPlanRuntime.ExecutionResult result,
                                         List<InteractionToolTrace> traces,
                                         List<String> observations,
                                         Map<String, Object> metadata) {
        planExecutionObservationCoordinator.record(
            stage, result, traces, observations, metadata);
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

    String templateSelectionFeedbackObservation(String stage,
                                                 InterpretationPlanRuntime.StepExecution step) {
        return planExecutionObservationCoordinator.templateSelectionFeedbackObservation(stage, step);
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
        return analysisRefinementCoordinator.evidenceDrivenRewriteLimit(
            configuredMaxRewriteTimes, outcome,
            !evidenceRefinementRequiredTools(evidenceHistory, availableTools).isEmpty());
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

    int initialRewriteLimit(int configuredMaxRewriteTimes,
                            EvidenceAugmentationPolicy.Outcome outcome,
                            boolean augmentationOverrideAvailable,
                            boolean executionRecoveryRequired,
                            boolean templateExecutionRetryRequested,
                            boolean toolsAvailable) {
        return analysisRefinementCoordinator.initialRewriteLimit(
            configuredMaxRewriteTimes, outcome, augmentationOverrideAvailable,
            executionRecoveryRequired, templateExecutionRetryRequested, toolsAvailable);
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
    private AgentOrchestrator.ToolCallExecution executePendingConfirmedTool(String query,
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
    private AgentOrchestrator.ToolCallExecution executeToolCall(String toolName,
                                              Map<String, Object> arguments,
                                              String conversationId,
                                              String requestId,
                                              String userId,
                                              String tenantId,
                                              List<String> allowedTools,
                                              Map<String, Object> plannerExecutionPlan,
                                              List<InteractionToolTrace> priorTraces,
                                              Map<String, Object> runtimeAttributes) {
        AgentToolExecutor.Execution execution = toolCallCoordinator.execute(
            toolName, arguments, conversationId, requestId, userId, tenantId,
            allowedTools, plannerExecutionPlan, priorTraces, runtimeAttributes);
        return new AgentOrchestrator.ToolCallExecution(
            execution.trace(), execution.observation(), execution.output());
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
        MandatoryWorkflowRecoveryCoordinator.Request recoveryRequest =
            new MandatoryWorkflowRecoveryCoordinator.Request(
                activeChatModel, traces, observations, query, conversationId, requestId,
                userId, tenantId, tools, mandatoryTools, documentIds, documentTags,
                webSearchResultLimit, metadata, runtimeAttributes, maxToolCalls,
                systemPrompt, cancellationCheck);
        mandatoryWorkflowRecoveryCoordinator.recover(
            recoveryRequest,
            this::executeToolCall,
            this::authoritativeWorkflowCandidateInput,
            (toolName, input, output) -> {
                MandatoryCandidateReview review = reviewMandatoryDiscoveryCandidates(
                    activeChatModel, query, systemPrompt, cancellationCheck,
                    toolName, input, output, runtimeAttributes);
                return new MandatoryWorkflowRecoveryCoordinator.SemanticReview(
                    review.required(), review.satisfied(), review.reason(),
                    review.projectedOutput(), review.auditMetadata());
            });
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
        return mandatoryWorkflowTopology.dependencyOrderedFallbackTools(
            authoritativeWorkflowDag, mcpWorkflow, mandatoryTools, completedTools);
    }

    List<String> dependencyOrderedMandatoryFallbackTools(Object authoritativeWorkflowDag,
                                                          List<String> mandatoryTools,
                                                          Set<String> completedTools) {
        return dependencyOrderedMandatoryFallbackTools(
            authoritativeWorkflowDag, null, mandatoryTools, completedTools);
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
            ),
            runtimeAttributes
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
        return mandatoryWorkflowRecoveryPolicy.shouldSuppressLegacyFallback(fallbackTool, metadata);
    }

    List<String> missingRequiredToolInputs(String toolName, Map<String, Object> arguments) {
        return mandatoryWorkflowRecoveryPolicy.missingRequiredInputs(toolName, arguments);
    }

    List<InteractionToolTrace> mandatoryPredecessorTraces(Object authoritativeWorkflowDag,
                                                           List<String> mandatoryTools,
                                                           String fallbackTool,
                                                           List<InteractionToolTrace> traces) {
        return mandatoryWorkflowTopology.predecessorTraces(
            authoritativeWorkflowDag, mandatoryTools, fallbackTool, traces);
    }

    protected Map<String, Object> mandatoryWorkflowResultReview(String toolName, ToolOutput output) {
        return mandatoryWorkflowResultReviewer.review(toolName, output);
    }
    private boolean templateExecutionRetryRequested(InterpretationPlanRuntime.ExecutionResult result) {
        return result != null && result.steps() != null && result.steps().stream()
            .filter(Objects::nonNull)
            .map(InterpretationPlanRuntime.StepExecution::metadata)
            .filter(Objects::nonNull)
            .anyMatch(metadata -> Boolean.TRUE.equals(metadata.get("templateExecutionRetryRequested")));
    }

    protected Map<String, Object> mandatoryWorkflowPredecessorReview(
        String fallbackTool,
        List<InteractionToolTrace> predecessorTraces
    ) {
        return mandatoryWorkflowResultReviewer.reviewPredecessors(fallbackTool, predecessorTraces);
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
            AgentOrchestrator.ToolCallExecution execution = executeToolCall(
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
        long now = System.currentTimeMillis();
        values.put("type", "lifecycle");
        values.put("workflow", WORKFLOW_PROBLEM_SOLVING);
        values.put("lifecyclePhase", phase);
        values.put("createdAt", now);
        values.putAll(phaseMetadata == null ? Map.of() : phaseMetadata);
        if (metadata != null) {
            List<Map<String, Object>> phases = metadataList(metadata, "agentLifecyclePhases");
            if (!phases.isEmpty()) {
                Map<String, Object> previous = phases.get(phases.size() - 1);
                long previousAt = runtimeGuard.runtimeLong(previous.get("createdAt"), now);
                long duration = Math.max(0L, now - previousAt);
                previous.put("durationMs", duration);
                @SuppressWarnings("unchecked")
                Map<String, Object> durations = metadata.get("agentPhaseDurationsMs") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : new LinkedHashMap<>();
                durations.merge(String.valueOf(previous.get("lifecyclePhase")), duration,
                    (left, right) -> ((Number) left).longValue() + ((Number) right).longValue());
                metadata.put("agentPhaseDurationsMs", durations);
            }
            phases.add(values);
            if (!phases.isEmpty()) {
                long firstAt = runtimeGuard.runtimeLong(phases.get(0).get("createdAt"), now);
                metadata.put("executionElapsedMs", Math.max(0L, now - firstAt));
            }
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

}
