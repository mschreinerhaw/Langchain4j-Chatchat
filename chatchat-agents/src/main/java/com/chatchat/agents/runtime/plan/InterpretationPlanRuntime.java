package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.protocol.McpToolProtocolRole;
import com.chatchat.agents.evidence.EvidenceExecutionLock;
import com.chatchat.agents.evidence.EvidenceLockGraph;
import com.chatchat.agents.runtime.evidence.DiagnosticEvidenceNormalizer;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.AgentRunStep;
import com.chatchat.agents.runtime.AgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.toolcall.ContextualToolArgumentResolver;
import com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler;
import com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge;
import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.routing.McpToolRouter;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.UUID;

/**
 * Executes validated InterpretationPlan DAGs against the MCP tool runtime.
 */
@Slf4j
public class InterpretationPlanRuntime {

    private static final String AGENT_RUN_ID_ATTRIBUTE = "__agentRunId";
    private static final String ORIGINAL_USER_QUERY_ATTRIBUTE = "originalUserQuery";
    private static final String AGENT_RUNTIME_ENVIRONMENT_ATTRIBUTE = "agentRuntimeEnvironment";
    private static final String MODEL_RETRIEVAL_GATE_KEY = "__modelRetrievalQualityGate";
    private static final String CONTEXT_PARAMETER_RECOVERY_KEY = "__runtimeContextParameterRecovery";
    private static final EvidenceBasedTemplateCandidateEvaluator TEMPLATE_CANDIDATE_EVALUATOR =
        new EvidenceBasedTemplateCandidateEvaluator();
    private static final EvidenceBasedAssetCandidateEvaluator ASSET_CANDIDATE_EVALUATOR =
        new EvidenceBasedAssetCandidateEvaluator();
    private static final Pattern EXPLICIT_ENV_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?iu)(?:\\benv(?:ironment)?\\b|\\u73af\\u5883)\\s*(?:[:=]|\\u4e3a|\\u662f)\\s*"
            + "(DEV|TEST|UAT|PROD|\\u5f00\\u53d1|\\u6d4b\\u8bd5|\\u9884\\u53d1|\\u751f\\u4ea7)"
    );
    private static final Pattern EXPLICIT_ENV_QUALIFIER_PATTERN = Pattern.compile(
        "(?iu)(DEV|TEST|UAT|PROD|\\u5f00\\u53d1|\\u6d4b\\u8bd5|\\u9884\\u53d1|\\u751f\\u4ea7)\\s*"
            + "(?:\\u73af\\u5883|\\u96c6\\u7fa4|\\benv(?:ironment)?\\b)"
    );
    private static final Pattern EXPLICIT_ENV_ENGLISH_PATTERN = Pattern.compile(
        "(?iu)\\b(?:in|on|under)\\s+(?:the\\s+)?(DEV|TEST|UAT|PROD)"
            + "(?:\\s+(?:env(?:ironment)?|cluster))?\\b"
    );
    private static final Pattern BINDING_PLACEHOLDER_PATTERN = Pattern.compile(
        "\\{\\{\\s*bindings\\.([A-Za-z0-9_.\\-\\[\\]]+)\\s*}}"
    );
    private static final Pattern RELATIVE_TODAY_PATTERN = Pattern.compile(
        "(?iu)(?:\\btoday\\b|\u4eca\u5929|\u4eca\u65e5|\u672c\u65e5)"
    );
    private static final Pattern EXPLICIT_CALENDAR_DATE_PATTERN = Pattern.compile(
        "(?iu)(?:\\b\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b|\\d{4}\u5e74\\d{1,2}\u6708\\d{1,2}\u65e5)"
    );
    private static final ObjectMapper RESULT_OBJECT_MAPPER = new ObjectMapper();
    private static final ToolArgumentCompiler TOOL_ARGUMENT_COMPILER = new ToolArgumentCompiler();
    private static final String RUNTIME_WORKER_ID = "worker-" + UUID.randomUUID();
    private static final long DEFAULT_NODE_LEASE_MS = 30_000L;
    private static final ScheduledExecutorService LEASE_HEARTBEATS = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "dag-node-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private static final ContextualToolArgumentResolver CONTEXT_ARGUMENT_RESOLVER =
        new ContextualToolArgumentResolver();
    private static final TemplateInvocationBridge TEMPLATE_INVOCATION_BRIDGE =
        new TemplateInvocationBridge();
    private static final DiagnosticEvidenceNormalizer DIAGNOSTIC_EVIDENCE_NORMALIZER =
        new DiagnosticEvidenceNormalizer();
    private static final Set<String> DISCOVERY_FILTER_PROTOCOL_FIELDS = Set.of(
        "trace",
        "routingTrace",
        "routing_trace",
        "candidates",
        "routingCandidates",
        "routing_candidates",
        "finalDecision",
        "final_decision",
        "selectedTargetKind",
        "selected_target_kind",
        "targetKind",
        "target_kind",
        "assetType",
        "asset_type",
        "confidence",
        "filtersSchemaVersion",
        "filters_schema_version",
        "mcpContext",
        "mcp_context",
        "tenantId",
        "tenant_id",
        "userId",
        "user_id",
        "requestId",
        "request_id",
        "conversationId",
        "conversation_id",
        "toolName",
        "tool_name",
        "remoteTool",
        "remote_tool"
    );
    private final ToolRuntimeService toolRuntimeService;
    private final InterpretationPlanValidator validator;
    private final InterpretationPlanOptimizer optimizer;
    private final AgentRunStore runStore;
    private final StepResultReviewer stepResultReviewer;
    private final DagExecutionController dagExecutionController;
    private final StepInputEnricher stepInputEnricher;
    private NodeAttemptStore nodeAttemptStore;
    private final McpToolRouter mcpToolRouter = new McpToolRouter();

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     DagExecutionController dagExecutionController) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), null, null, dagExecutionController);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     AgentRunStore runStore,
                                     DagExecutionController dagExecutionController) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), runStore, null, dagExecutionController);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     AgentRunStore runStore,
                                     StepResultReviewer stepResultReviewer,
                                     DagExecutionController dagExecutionController) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), runStore, stepResultReviewer, dagExecutionController);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     AgentRunStore runStore,
                                     StepResultReviewer stepResultReviewer,
                                     DagExecutionController dagExecutionController,
                                     StepInputEnricher stepInputEnricher) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), runStore,
            stepResultReviewer, dagExecutionController, stepInputEnricher);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     InterpretationPlanOptimizer optimizer,
                                     DagExecutionController dagExecutionController) {
        this(toolRuntimeService, validator, optimizer, null, null, dagExecutionController);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     InterpretationPlanOptimizer optimizer,
                                     AgentRunStore runStore,
                                     StepResultReviewer stepResultReviewer,
                                     DagExecutionController dagExecutionController) {
        this(toolRuntimeService, validator, optimizer, runStore, stepResultReviewer,
            dagExecutionController, null);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     InterpretationPlanOptimizer optimizer,
                                     AgentRunStore runStore,
                                     StepResultReviewer stepResultReviewer,
                                     DagExecutionController dagExecutionController,
                                     StepInputEnricher stepInputEnricher) {
        this.toolRuntimeService = toolRuntimeService;
        this.validator = validator == null ? new InterpretationPlanValidator() : validator;
        this.optimizer = optimizer == null ? new InterpretationPlanOptimizer() : optimizer;
        this.runStore = runStore;
        this.stepResultReviewer = stepResultReviewer;
        this.dagExecutionController = dagExecutionController;
        this.stepInputEnricher = stepInputEnricher;
    }

    public void setNodeAttemptStore(NodeAttemptStore nodeAttemptStore) {
        this.nodeAttemptStore = nodeAttemptStore;
    }

    /**
     * Executes the plan as a DAG.
     *
     * @param request the execution request
     * @return the execution result
     */
    public ExecutionResult execute(ExecutionRequest request) {
        long startedAt = System.currentTimeMillis();
        if (request == null || request.plan() == null) {
            return ExecutionResult.failed("INVALID_REQUEST", "Execution request and plan are required", List.of(), Map.of(), null, 0L);
        }
        Object authoritativeWorkflowDag = request.attributes() == null
            ? null : request.attributes().get("authoritativeWorkflowDag");
        String authoritativeWorkflowTaskId = request.attributes() == null
            ? null : stringValue(request.attributes().get("authoritativeWorkflowTaskId"));
        InterpretationPlanOptimizer.OptimizationResult optimization = optimizer.optimize(
            request.plan(), authoritativeWorkflowDag);
        InterpretationPlan executablePlan = optimization.plan() == null ? request.plan() : optimization.plan();
        String executionTraceId = executionTraceId(request, startedAt);
        ExecutionRequest executableRequest = request.withPlanAndAttributes(
            executablePlan,
            attributesWithProtocol(request.attributes(), executionTraceId)
        );
        InterpretationPlanValidator.ValidationResult validation = validator.validate(
            executablePlan,
            request.toolRegistry(),
            new LinkedHashSet<>(safeList(request.allowedTools())),
            authoritativeWorkflowDag,
            authoritativeWorkflowTaskId
        );
        if (!validation.valid()) {
            return withDiagnosticRun(ExecutionResult.failed(
                "INVALID_PLAN",
                validation.errors().stream().map(InterpretationPlanValidator.ValidationIssue::message).collect(Collectors.joining("; ")),
                List.of(),
                Map.of("validationIssues", validation.issues()),
                null,
                elapsed(startedAt)
            ), executableRequest, planStepIds(executablePlan));
        }
        if (validation.approvalRequired()) {
            return withDiagnosticRun(ExecutionResult.approvalRequired(
                validation.approvalRequests(),
                List.of(),
                Map.of("validationIssues", validation.issues()),
                elapsed(startedAt)
            ), executableRequest, planStepIds(executablePlan));
        }
        Map<Integer, InterpretationPlan.Step> stepsById = executablePlan.steps().stream()
            .filter(step -> step != null && step.id() != null)
            .collect(Collectors.toMap(
                InterpretationPlan.Step::id,
                step -> step,
                (left, ignored) -> left,
                LinkedHashMap::new
            ));
        Map<Integer, StepExecution> completed = new LinkedHashMap<>();
        Set<Integer> remaining = new LinkedHashSet<>(stepsById.keySet());
        List<StepExecution> executions = new ArrayList<>();
        String runId = runId(executableRequest);
        Set<Integer> reusedPlanStepIds = seedReusableStepExecutions(
            executableRequest, stepsById, completed, executions);
        CheckpointRecovery checkpointRecovery = seedPersistedStepCheckpoints(
            runId, executableRequest, stepsById, completed);
        reusedPlanStepIds.addAll(checkpointRecovery.stepIds());
        List<StepExecution> toleratedFailures = new ArrayList<>();
        Set<Integer> failureRegionSkippedStepIds = new LinkedHashSet<>();
        Set<Integer> semanticBranchSkippedStepIds = new LinkedHashSet<>();
        String finalAnswer = completed.values().stream()
            .map(StepExecution::finalAnswer)
            .filter(value -> value != null && !value.isBlank())
            .reduce((ignored, value) -> value)
            .orElse(null);
        int decisionCount = 0;
        int llmDecisionCount = 0;

        while (!remaining.isEmpty()) {
            InterpretationPlanEventState eventState = !"NONE".equals(checkpointRecovery.status())
                ? new InterpretationPlanEventState(Set.of(), Set.of(), Set.of(), Set.of(), Set.of())
                : eventState(runId, completed.keySet(), executableRequest);
            Set<Integer> storedCompletedStepIds = new LinkedHashSet<>(completed.keySet());
            storedCompletedStepIds.addAll(eventState.completedStepIds());
            List<StepExecution> hydratedExecutions = hydrateCompletedExecutionsFromEvents(
                runId, storedCompletedStepIds, completed, stepsById, executableRequest);
            for (StepExecution hydrated : hydratedExecutions) {
                if (hydrated.finalAnswer() != null && !hydrated.finalAnswer().isBlank()) {
                    finalAnswer = hydrated.finalAnswer();
                }
            }
            Set<Integer> completedStepIds = new LinkedHashSet<>(completed.keySet());
            remaining.removeAll(completedStepIds);
            if (remaining.isEmpty()) {
                break;
            }
            int currentDecisionCount = ++decisionCount;
            DagDecision decision = deterministicEvidenceRecoveryDecision(
                executablePlan,
                remaining,
                stepsById,
                completed,
                executableRequest,
                currentDecisionCount,
                executionTraceId
            );
            List<Integer> readyStepIds = readyStepIds(remaining, stepsById, completedStepIds);
            SemanticBranch semanticBranch = semanticBranch(executablePlan, readyStepIds);
            String decisionPurpose = "DETERMINISTIC_SCHEDULING";
            if (decision == null) {
                decision = deterministicReadyDecision(
                    executablePlan,
                    readyStepIds,
                    stepsById,
                    completed,
                    semanticBranch,
                    currentDecisionCount,
                    executionTraceId
                );
            }
            if (decision == null) {
                decisionPurpose = semanticBranch.required()
                    ? "SEMANTIC_BRANCH_ARBITRATION" : "TEMPLATE_PARAMETER_BINDING";
                List<Integer> legalCandidateStepIds = semanticBranch.required()
                    ? semanticBranch.candidateStepIds()
                    : readyStepIds.stream()
                        .filter(stepId -> requiresModelTemplateParameterProtocol(stepsById.get(stepId), completed))
                        .toList();
                if (legalCandidateStepIds.isEmpty()) {
                    decision = DagDecision.rewritePlan(
                        "Runtime found no Ready nodes while unfinished DAG nodes remain; repair is required."
                    );
                    decisionPurpose = "DAG_REPAIR";
                } else if (dagExecutionController == null) {
                    return withDiagnosticRun(ExecutionResult.failed(
                        "DAG_CONTROLLER_REQUIRED",
                        "LLM arbitration is required for Ready nodes " + legalCandidateStepIds,
                        executions,
                        Map.of(
                            "readyStepIds", legalCandidateStepIds,
                            "decisionPurpose", decisionPurpose,
                            "remainingStepIds", new ArrayList<>(remaining)
                        ),
                        finalAnswer,
                        elapsed(startedAt)
                    ), executableRequest, remaining);
                } else {
                    llmDecisionCount++;
                    decision = dagExecutionController.decide(new DagDecisionRequest(
                        executablePlan,
                        new LinkedHashSet<>(remaining),
                        new LinkedHashSet<>(legalCandidateStepIds),
                        Map.copyOf(completed),
                        List.copyOf(executions),
                        completedStepIds,
                        currentDecisionCount,
                        InterpretationExecutionProtocol.VERSION,
                        executionTraceId,
                        finalAnswer,
                        decisionPurpose
                    ));
                }
            }
            List<Integer> legalReadyList = semanticBranch.required()
                && "SEMANTIC_BRANCH_ARBITRATION".equals(decisionPurpose)
                ? semanticBranch.candidateStepIds()
                : "TEMPLATE_PARAMETER_BINDING".equals(decisionPurpose)
                    ? readyStepIds.stream()
                        .filter(stepId -> requiresModelTemplateParameterProtocol(stepsById.get(stepId), completed))
                        .toList()
                    : readyStepIds;
            Set<Integer> legalReadyStepIds = new LinkedHashSet<>(legalReadyList);
            DecisionValidation decisionValidation = validateDecision(
                decision, executablePlan, remaining, stepsById, completedStepIds, legalReadyStepIds);
            if (decisionValidation.valid()
                && semanticBranch.required()
                && "SEMANTIC_BRANCH_ARBITRATION".equals(decisionPurpose)
                && !Set.of("abort", "rewrite_plan").contains(decisionValidation.action())
                && decisionValidation.steps().size() != 1) {
                decisionValidation = DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "Semantic branch arbitration must select exactly one Ready candidate from "
                        + semanticBranch.candidateStepIds()
                );
            }
            recordControllerDecision(
                executableRequest,
                executionTraceId,
                currentDecisionCount,
                decision,
                decisionValidation,
                remaining,
                completedStepIds
            );
            if (!decisionValidation.valid()) {
                return withDiagnosticRun(ExecutionResult.failed(
                    decisionValidation.status(),
                    decisionValidation.message(),
                    executions,
                    Map.of(
                        "protocolVersion", InterpretationExecutionProtocol.VERSION,
                        "executionTraceId", executionTraceId,
                        "remainingStepIds", new ArrayList<>(remaining),
                        "completedStepIds", new ArrayList<>(completedStepIds),
                        "decisionCount", currentDecisionCount,
                        "controllerDecision", decision == null ? Map.of() : decisionMetadata(decision),
                        "guardResult", guardResultMetadata(decisionValidation)
                    ),
                    finalAnswer,
                    elapsed(startedAt)
                ), executableRequest, remaining);
            }
            if ("abort".equals(decisionValidation.action())) {
                return withDiagnosticRun(ExecutionResult.failed(
                    "DAG_ABORTED",
                    decision.reason() == null || decision.reason().isBlank() ? "LLM DAG controller aborted execution" : decision.reason(),
                    executions,
                    Map.of(
                        "protocolVersion", InterpretationExecutionProtocol.VERSION,
                        "executionTraceId", executionTraceId,
                        "remainingStepIds", new ArrayList<>(remaining),
                        "completedStepIds", new ArrayList<>(completedStepIds),
                        "decisionCount", currentDecisionCount,
                        "controllerDecision", decisionMetadata(decision),
                        "guardResult", guardResultMetadata(decisionValidation)
                    ),
                    firstText(decision.finalAnswer(), finalAnswer),
                    elapsed(startedAt)
                ), executableRequest, remaining);
            }
            if ("rewrite_plan".equals(decisionValidation.action())) {
                return withDiagnosticRun(ExecutionResult.failed(
                    "DAG_REWRITE_REQUESTED",
                    decision.reason() == null || decision.reason().isBlank() ? "LLM DAG controller requested plan rewrite" : decision.reason(),
                    executions,
                    Map.of(
                        "protocolVersion", InterpretationExecutionProtocol.VERSION,
                        "executionTraceId", executionTraceId,
                        "remainingStepIds", new ArrayList<>(remaining),
                        "completedStepIds", new ArrayList<>(completedStepIds),
                        "decisionCount", currentDecisionCount,
                        "controllerDecision", decisionMetadata(decision),
                        "guardResult", guardResultMetadata(decisionValidation)
                    ),
                    firstText(decision.finalAnswer(), finalAnswer),
                    elapsed(startedAt)
                ), executableRequest, remaining);
            }
            List<InterpretationPlan.Step> selected = applyDecisionParameterProtocols(
                decisionValidation.steps(),
                decision
            );
            if (semanticBranch.required() && "SEMANTIC_BRANCH_ARBITRATION".equals(decisionPurpose)) {
                Set<Integer> selectedStepIds = selected.stream()
                    .map(InterpretationPlan.Step::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                Set<Integer> skipped = new LinkedHashSet<>(semanticBranch.candidateStepIds());
                skipped.removeAll(selectedStepIds);
                remaining.removeAll(skipped);
                semanticBranchSkippedStepIds.addAll(skipped);
                log.info("InterpretationPlan semantic branch resolved: traceId={}, targetStepId={}, selectedStepIds={}, skippedStepIds={}",
                    executionTraceId, semanticBranch.targetStepId(), selectedStepIds, skipped);
            }
            String executionEpoch = executionTraceId + ":epoch:" + currentDecisionCount;
            List<StepExecution> waveResults = executeWave(
                selected, executableRequest, completed, executionEpoch);
            for (StepExecution execution : waveResults) {
                executions.add(execution);
                completed.put(execution.stepId(), execution);
                remaining.remove(execution.stepId());
            }
            StepExecution contractFailure = validateEdgeContracts(
                executablePlan, waveResults, completed, executableRequest);
            if (contractFailure != null) {
                waveResults = rejectPreparedWave(waveResults, executableRequest, executionEpoch,
                    "edge contract validation failed");
                replaceWaveResults(executions, completed, waveResults);
                executions.add(contractFailure);
                return withDiagnosticRun(ExecutionResult.failed(
                    "EDGE_CONTRACT_FAILED",
                    contractFailure.errorMessage(),
                    executions,
                    Map.of(
                        "failedStepId", contractFailure.stepId(),
                        "optimizationPasses", optimization.appliedPasses()
                    ),
                    finalAnswer,
                    elapsed(startedAt)
                ), executableRequest, remaining);
            }
            StepExecution preBarrierFailure = waveResults.stream()
                .filter(step -> !step.success())
                .findFirst()
                .orElse(null);
            if (preBarrierFailure != null) {
                boolean independentCommitAllowed = dagGovernanceBoolean(
                    executableRequest, "execution", "continueIndependentBranches", false)
                    || waveResults.stream().filter(step -> !step.success())
                        .anyMatch(step -> "continue_with_partial_evidence".equals(
                            dependencyFailurePolicy(executablePlan, step.stepId())));
                if (independentCommitAllowed) {
                    List<StepExecution> prepared = waveResults.stream().filter(StepExecution::success).toList();
                    List<StepExecution> committedPrepared = commitPreparedWave(
                        prepared, executableRequest, executionEpoch);
                    Map<Integer, StepExecution> committedByStep = committedPrepared.stream()
                        .collect(Collectors.toMap(StepExecution::stepId, value -> value));
                    waveResults = waveResults.stream()
                        .map(step -> committedByStep.getOrDefault(step.stepId(), step))
                        .toList();
                } else {
                    waveResults = rejectPreparedWave(waveResults, executableRequest, executionEpoch,
                        "required node failed before commit barrier");
                }
            } else {
                waveResults = commitPreparedWave(waveResults, executableRequest, executionEpoch);
            }
            replaceWaveResults(executions, completed, waveResults);
            for (StepExecution committedExecution : waveResults) {
                boolean committedEvidence = nodeAttemptStore == null
                    || Boolean.TRUE.equals(committedExecution.metadata().get("committedEvidence"));
                if (committedEvidence && committedExecution.success()
                    && committedExecution.finalAnswer() != null
                    && !committedExecution.finalAnswer().isBlank()) {
                    finalAnswer = committedExecution.finalAnswer();
                }
            }
            persistSuccessfulStepCheckpoints(runId, executableRequest, stepsById, completed, waveResults);
            StepExecution failed = waveResults.stream()
                .filter(step -> !step.success())
                .findFirst()
                .orElse(null);
            recordStateUpdate(executableRequest, completed, remaining, waveResults, failed);
            if (failed != null) {
                String failurePolicy = dependencyFailurePolicy(executablePlan, failed.stepId());
                boolean recoverableReviewedBatch = hasRecoverableReviewedTemplateBatchDownstream(
                    executablePlan, failed.stepId(), completed);
                if ("continue_with_partial_evidence".equals(failurePolicy) || recoverableReviewedBatch) {
                    toleratedFailures.add(failed);
                    log.warn("InterpretationPlan continuing after step failure under dependency policy: "
                            + "traceId={}, stepId={}, tool={}, policy={}, reviewedBatchRecovery={}, remainingStepIds={}, error={}",
                        executionTraceId, failed.stepId(), failed.toolName(), failurePolicy, recoverableReviewedBatch,
                        new ArrayList<>(remaining), failed.errorMessage());
                    continue;
                }
                if (dagGovernanceBoolean(executableRequest, "execution",
                    "continueIndependentBranches", false)) {
                    Set<Integer> failedStepIds = waveResults.stream()
                        .filter(step -> step != null && !step.success() && step.stepId() != null)
                        .map(StepExecution::stepId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                    Set<Integer> blockedRegion = descendantStepIds(executablePlan, failedStepIds);
                    blockedRegion.retainAll(remaining);
                    Set<Integer> independentRemaining = new LinkedHashSet<>(remaining);
                    independentRemaining.removeAll(blockedRegion);
                    if (!independentRemaining.isEmpty()) {
                        remaining.removeAll(blockedRegion);
                        failureRegionSkippedStepIds.addAll(blockedRegion);
                        waveResults.stream()
                            .filter(step -> step != null && !step.success())
                            .forEach(toleratedFailures::add);
                        log.warn("InterpretationPlan isolated failed DAG region and continued independent branches: "
                                + "traceId={}, failedStepIds={}, skippedDescendantStepIds={}, independentStepIds={}",
                            executionTraceId, failedStepIds, blockedRegion, independentRemaining);
                        continue;
                    }
                }
                String failureStatus = failed.errorMessage() != null
                    && failed.errorMessage().startsWith(
                        DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED.wireValue() + ":")
                    ? DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED.wireValue()
                    : "STEP_FAILED";
                return withDiagnosticRun(ExecutionResult.failed(
                    failureStatus,
                    failed.errorMessage(),
                    executions,
                    Map.of(
                        "failedStepId", failed.stepId(),
                        "failedTool", failed.toolName(),
                        "optimizationPasses", optimization.appliedPasses()
                    ),
                    finalAnswer,
                    elapsed(startedAt)
                ), executableRequest, remaining);
            }
        }

        if (executions.isEmpty() && completed.isEmpty() && !stepsById.isEmpty()) {
            log.warn("InterpretationPlan made no execution progress: traceId={}, attempt={}, scope={}, plannedStepIds={}",
                executionTraceId, workflowExecutionAttempt(executableRequest),
                planExecutionScope(executableRequest), new ArrayList<>(stepsById.keySet()));
            return withDiagnosticRun(ExecutionResult.failed(
                "DAG_NO_PROGRESS",
                "Validated InterpretationPlan completed without executing or restoring any current-scope step",
                List.of(),
                Map.of(
                    "protocolVersion", InterpretationExecutionProtocol.VERSION,
                    "executionTraceId", executionTraceId,
                    "workflowExecutionAttempt", workflowExecutionAttempt(executableRequest),
                    "planExecutionScope", planExecutionScope(executableRequest),
                    "requiredPlanStepIds", new ArrayList<>(stepsById.keySet()),
                    "decisionCount", decisionCount
                ),
                finalAnswer,
                elapsed(startedAt)
            ), executableRequest, remaining);
        }

        String completionStatus = toleratedFailures.isEmpty()
            ? "completed" : "completed_with_partial_evidence";
        return withDiagnosticRun(new ExecutionResult(
            completionStatus,
            true,
            false,
            null,
            finalAnswer,
            executions,
            mapOf(
                "protocolVersion", InterpretationExecutionProtocol.VERSION,
                "executionTraceId", executionTraceId,
                "workflowExecutionAttempt", workflowExecutionAttempt(executableRequest),
                "planExecutionScope", planExecutionScope(executableRequest),
                "stepCount", executions.size(),
                "requiredPlanStepIds", new ArrayList<>(stepsById.keySet()),
                "completedPlanStepIds", new ArrayList<>(completed.keySet()),
                "reusedPlanStepIds", new ArrayList<>(reusedPlanStepIds),
                "recoveryStatus", checkpointRecovery.status(),
                "resumedPlanStepIds", new ArrayList<>(checkpointRecovery.stepIds()),
                "resumeToken", checkpointRecovery.resumeToken(),
                "recoveryAttemptIds", new ArrayList<>(checkpointRecovery.attemptIds()),
                "recoveryRejectedReason", checkpointRecovery.rejectedReason(),
                "remainingPlanStepIds", new ArrayList<>(remaining),
                "parallel", allowParallel(executablePlan),
                "decisionCount", decisionCount,
                "llmDagController", llmDecisionCount > 0,
                "llmDagDecisionCount", llmDecisionCount,
                "optimizationPasses", optimization.appliedPasses(),
                "continuedFailureStepIds", toleratedFailures.stream().map(StepExecution::stepId).toList(),
                "failureRegionSkippedStepIds", new ArrayList<>(failureRegionSkippedStepIds),
                "semanticBranchSkippedStepIds", new ArrayList<>(semanticBranchSkippedStepIds),
                "partialEvidence", !toleratedFailures.isEmpty()
            ),
            elapsed(startedAt)
        ), executableRequest, remaining);
    }

    private Set<Integer> seedReusableStepExecutions(ExecutionRequest request,
                                                    Map<Integer, InterpretationPlan.Step> stepsById,
                                                    Map<Integer, StepExecution> completed,
                                                    List<StepExecution> executions) {
        Set<Integer> reusedStepIds = new LinkedHashSet<>();
        if (request == null || request.attributes() == null) {
            return reusedStepIds;
        }
        Object raw = request.attributes().get("reusablePlanSteps");
        if (!(raw instanceof Iterable<?> reusableSteps)) {
            return reusedStepIds;
        }
        for (Object value : reusableSteps) {
            if (!(value instanceof ReusableStep reusable) || reusable.step() == null
                || reusable.execution() == null || !reusable.execution().success()
                || reusable.step().id() == null
                || !Objects.equals(reusable.step(), stepsById.get(reusable.step().id()))) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(reusable.execution().metadata());
            metadata.put("reusedFromPlanRevision", true);
            StepExecution restored = new StepExecution(
                reusable.execution().stepId(), reusable.execution().actionType(),
                reusable.execution().toolName(), true, reusable.execution().output(), null,
                reusable.execution().toolExecution(), reusable.execution().finalAnswer(), 0L,
                Map.copyOf(metadata)
            );
            completed.put(restored.stepId(), restored);
            executions.add(restored);
            reusedStepIds.add(restored.stepId());
        }
        return reusedStepIds;
    }

    private CheckpointRecovery seedPersistedStepCheckpoints(String runId,
                                                            ExecutionRequest request,
                                                            Map<Integer, InterpretationPlan.Step> stepsById,
                                                            Map<Integer, StepExecution> completed) {
        Set<Integer> reusedStepIds = new LinkedHashSet<>();
        if (runStore == null || runId == null || runId.isBlank() || stepsById.isEmpty()) {
            return CheckpointRecovery.none();
        }
        List<PlanStepCheckpoint> stored;
        try {
            stored = runStore.planStepCheckpoints(runId);
        } catch (RuntimeException ex) {
            log.warn("Failed to load persisted plan checkpoints. runId={} error={}", runId, ex.getMessage());
            return CheckpointRecovery.rejected("CHECKPOINT_STORE_UNAVAILABLE");
        }
        Map<Integer, PlanStepCheckpoint> byStepId = stored.stream()
            .filter(Objects::nonNull)
            .filter(checkpoint -> checkpoint.stepId() != null)
            .collect(Collectors.toMap(
                PlanStepCheckpoint::stepId,
                checkpoint -> checkpoint,
                (left, right) -> left.updatedAt() >= right.updatedAt() ? left : right,
                LinkedHashMap::new
            ));
        String requestedToken = request == null || request.attributes() == null
            ? null : stringValue(request.attributes().get("resumeToken"));
        Set<String> committedAttemptIds = committedAttemptIds(request, runId);
        boolean reconcileAttempts = nodeAttemptStore != null && nodeAttemptStore.supportsRecoveryQueries();
        Map<Integer, StepExecution> recovered = new LinkedHashMap<>(completed);
        Set<String> recoveryAttemptIds = new LinkedHashSet<>();
        boolean progressed;
        do {
            progressed = false;
            for (InterpretationPlan.Step step : stepsById.values()) {
                if (recovered.containsKey(step.id())) {
                    continue;
                }
                PlanStepCheckpoint checkpoint = byStepId.get(step.id());
                String attemptId = checkpointAttemptId(checkpoint);
                if (reconcileAttempts && (attemptId == null || !committedAttemptIds.contains(attemptId))) {
                    continue;
                }
                if (!validCheckpoint(checkpoint, step, recovered, request)) {
                    continue;
                }
                StepExecution materialized = checkpoint.materializedResult();
                Map<String, Object> metadata = new LinkedHashMap<>(materialized.metadata());
                metadata.put("reusedFromCheckpoint", true);
                metadata.put("checkpointSchemaVersion", checkpoint.schemaVersion());
                metadata.put("checkpointUpdatedAt", checkpoint.updatedAt());
                metadata.put("checkpointFingerprint", checkpoint.checkpointFingerprint());
                metadata.put("checkpointIdentityFingerprints", checkpoint.identityFingerprints());
                StepExecution restored = new StepExecution(
                    materialized.stepId(), materialized.actionType(), materialized.toolName(), true,
                    materialized.output(), null, materialized.toolExecution(), materialized.finalAnswer(),
                    0L, Map.copyOf(metadata)
                );
                recovered.put(restored.stepId(), restored);
                reusedStepIds.add(restored.stepId());
                if (attemptId != null) {
                    recoveryAttemptIds.add(attemptId);
                }
                progressed = true;
            }
        } while (progressed);
        if (reusedStepIds.isEmpty()) {
            return requestedToken == null || requestedToken.isBlank()
                ? CheckpointRecovery.none()
                : CheckpointRecovery.rejected("NO_CONSISTENT_COMMITTED_BOUNDARY");
        }
        String resumeToken = recoveryToken(request, byStepId, reusedStepIds, recoveryAttemptIds);
        if (requestedToken != null && !requestedToken.isBlank() && !requestedToken.equals(resumeToken)) {
            log.warn("Rejected plan recovery because resume token does not match the latest consistent boundary. "
                    + "runId={} recoveredStepIds={}", runId, reusedStepIds);
            return CheckpointRecovery.rejected("RESUME_TOKEN_MISMATCH");
        }
        completed.putAll(recovered);
        if (!reusedStepIds.isEmpty()) {
            log.info("Restored plan from latest consistent checkpoint boundary. runId={} stepIds={} "
                    + "attemptReconciled={} resumeToken={}",
                runId, reusedStepIds, reconcileAttempts, resumeToken);
        }
        return new CheckpointRecovery("RESUMED", resumeToken,
            Collections.unmodifiableSet(new LinkedHashSet<>(reusedStepIds)),
            Collections.unmodifiableSet(new LinkedHashSet<>(recoveryAttemptIds)), null);
    }

    private Set<String> committedAttemptIds(ExecutionRequest request, String runId) {
        if (nodeAttemptStore == null || !nodeAttemptStore.supportsRecoveryQueries()) {
            return Set.of();
        }
        try {
            return nodeAttemptStore.committedAttempts(request.tenantId(), runId).stream()
                .filter(Objects::nonNull)
                .filter(attempt -> attempt.state() == NodeAttemptStore.State.COMMITTED)
                .map(NodeAttemptStore.AttemptSnapshot::attemptId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException ex) {
            log.warn("Failed to reconcile committed node Attempts. runId={} error={}", runId, ex.getMessage());
            return Set.of();
        }
    }

    private String checkpointAttemptId(PlanStepCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.materializedResult() == null
            || checkpoint.materializedResult().metadata() == null) {
            return null;
        }
        Map<String, Object> metadata = checkpoint.materializedResult().metadata();
        if (metadata.get("nodeAttemptState") != null
            && !NodeAttemptStore.State.COMMITTED.name().equals(String.valueOf(metadata.get("nodeAttemptState")))) {
            return null;
        }
        return stringValue(metadata.get("nodeAttemptId"));
    }

    private String recoveryToken(ExecutionRequest request,
                                 Map<Integer, PlanStepCheckpoint> checkpoints,
                                 Set<Integer> stepIds,
                                 Set<String> attemptIds) {
        Map<Integer, String> checkpointFingerprints = new LinkedHashMap<>();
        stepIds.stream().sorted().forEach(stepId -> {
            PlanStepCheckpoint checkpoint = checkpoints.get(stepId);
            if (checkpoint != null) {
                checkpointFingerprints.put(stepId, checkpoint.checkpointFingerprint());
            }
        });
        return "resume.v1." + sha256(mapOf(
            "runId", runId(request),
            "planExecutionScope", planExecutionScope(request),
            "workflowExecutionAttempt", normalizedWorkflowExecutionAttempt(workflowExecutionAttempt(request)),
            "checkpointFingerprints", checkpointFingerprints,
            "attemptIds", attemptIds.stream().sorted().toList()
        ));
    }

    private record CheckpointRecovery(String status,
                                      String resumeToken,
                                      Set<Integer> stepIds,
                                      Set<String> attemptIds,
                                      String rejectedReason) {
        private static CheckpointRecovery none() {
            return new CheckpointRecovery("NONE", null, Set.of(), Set.of(), null);
        }

        private static CheckpointRecovery rejected(String reason) {
            return new CheckpointRecovery("REJECTED", null, Set.of(), Set.of(), reason);
        }
    }

    private boolean validCheckpoint(PlanStepCheckpoint checkpoint,
                                    InterpretationPlan.Step step,
                                    Map<Integer, StepExecution> completed,
                                    ExecutionRequest request) {
        try {
            if (checkpoint == null || step == null || checkpoint.materializedResult() == null) {
                return false;
            }
            for (Integer dependencyId : step.dependsOn() == null ? List.<Integer>of() : step.dependsOn()) {
                if (!completed.containsKey(dependencyId)) {
                    return false;
                }
            }
            Map<String, String> expectedIdentity = checkpointIdentityFingerprints(
                step, request, completed, null);
            if (!checkpoint.materializedResult().success()
                || !PlanStepCheckpoint.SCHEMA_VERSION.equals(checkpoint.schemaVersion())
                || !checkpoint.committed()
                || !Objects.equals(checkpoint.planExecutionScope(), planExecutionScope(request))
                || !Objects.equals(checkpoint.workflowExecutionAttempt(),
                    normalizedWorkflowExecutionAttempt(workflowExecutionAttempt(request)))
                || !Objects.equals(step.id(), checkpoint.stepId())
                || !Objects.equals(stepFingerprint(step), checkpoint.definitionFingerprint())
                || !Objects.equals(expectedIdentity, checkpoint.identityFingerprints())
                || !Objects.equals(sha256(expectedIdentity), checkpoint.checkpointFingerprint())
                || !Objects.equals(resultFingerprint(checkpoint.materializedResult()), checkpoint.resultFingerprint())) {
                return false;
            }
            for (Integer dependencyId : step.dependsOn() == null ? List.<Integer>of() : step.dependsOn()) {
                StepExecution dependency = completed.get(dependencyId);
                String expected = checkpoint.dependencyResultFingerprints().get(dependencyId);
                if (dependency == null || expected == null
                    || !Objects.equals(expected, resultFingerprint(dependency))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("Ignored unreadable plan checkpoint. stepId={} error={}",
                step == null ? null : step.id(), ex.getMessage());
            return false;
        }
    }

    private void persistSuccessfulStepCheckpoints(String runId,
                                                  ExecutionRequest request,
                                                  Map<Integer, InterpretationPlan.Step> stepsById,
                                                  Map<Integer, StepExecution> completed,
                                                  List<StepExecution> waveResults) {
        if (runStore == null || runId == null || runId.isBlank() || waveResults == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (StepExecution execution : waveResults) {
            InterpretationPlan.Step step = execution == null ? null : stepsById.get(execution.stepId());
            if (step == null || !execution.success()) {
                continue;
            }
            try {
                Map<Integer, String> dependencyFingerprints = new LinkedHashMap<>();
                boolean dependenciesMaterialized = true;
                for (Integer dependencyId : step.dependsOn() == null ? List.<Integer>of() : step.dependsOn()) {
                    StepExecution dependency = completed.get(dependencyId);
                    if (dependency == null || !dependency.success()) {
                        dependenciesMaterialized = false;
                        break;
                    }
                    dependencyFingerprints.put(dependencyId, resultFingerprint(dependency));
                }
                if (!dependenciesMaterialized) {
                    continue;
                }
                Map<String, String> identityFingerprints = checkpointIdentityFingerprints(
                    step, request, completed, execution);
                PlanStepCheckpoint checkpoint = new PlanStepCheckpoint(
                    PlanStepCheckpoint.SCHEMA_VERSION,
                    runId,
                    planExecutionScope(request),
                    normalizedWorkflowExecutionAttempt(workflowExecutionAttempt(request)),
                    step.id(),
                    stepFingerprint(step),
                    sha256(identityFingerprints),
                    identityFingerprints,
                    dependencyFingerprints,
                    resultFingerprint(execution),
                    execution,
                    true,
                    now,
                    now
                );
                runStore.savePlanStepCheckpoint(checkpoint);
            } catch (RuntimeException ex) {
                log.warn("Failed to persist plan step checkpoint. runId={} stepId={} error={}",
                    runId, step.id(), ex.getMessage());
            }
        }
    }

    private String stepFingerprint(InterpretationPlan.Step step) {
        return sha256(step);
    }

    private Map<String, String> checkpointIdentityFingerprints(InterpretationPlan.Step step,
                                                               ExecutionRequest request,
                                                               Map<Integer, StepExecution> completed,
                                                               StepExecution materializedExecution) {
        Map<Integer, String> dependencyFingerprints = new LinkedHashMap<>();
        if (step != null) {
            for (Integer dependencyId : step.dependsOn() == null ? List.<Integer>of() : step.dependsOn()) {
                StepExecution dependency = completed == null ? null : completed.get(dependencyId);
                if (dependency != null && dependency.success()) {
                    dependencyFingerprints.put(dependencyId, resultFingerprint(dependency));
                }
            }
        }
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("planVersion", sha256(request == null || request.plan() == null
            ? null : request.plan().version()));
        identity.put("nodeDefinition", stepFingerprint(step));
        identity.put("actualInput", sha256(checkpointActualInput(
            step, request, completed, materializedExecution)));
        identity.put("dependencyResults", sha256(dependencyFingerprints));
        identity.put("toolContract", sha256(checkpointToolContract(step, request)));
        identity.put("modelConfig", checkpointContextFingerprint(
            request, "checkpointModelConfigFingerprint", "checkpointModelConfig",
            List.of("modelName", "modelProvider", "modelConfigVersion", "modelTemperature",
                "modelTopP", "modelSeed", "modelEndpointId", "modelImplementation")));
        identity.put("governanceContract", sha256(dagGovernanceContract(request)));
        identity.put("executionEnvironment", checkpointContextFingerprint(
            request, "checkpointExecutionEnvironmentFingerprint", "checkpointExecutionEnvironment",
            List.of("env", "environment", "runtimeProfile", "deploymentId", "region", "timezone",
                "applicationVersion", "runtimeVersion")));
        return Map.copyOf(identity);
    }

    private Object checkpointActualInput(InterpretationPlan.Step step,
                                         ExecutionRequest request,
                                         Map<Integer, StepExecution> completed,
                                         StepExecution materializedExecution) {
        if (materializedExecution != null && materializedExecution.metadata() != null
            && materializedExecution.metadata().get("resolvedInput") != null) {
            return materializedExecution.metadata().get("resolvedInput");
        }
        if (step == null || !step.mcpToolAction()) {
            return step == null || step.input() == null ? Map.of() : step.input();
        }
        Map<String, Object> resolved = new LinkedHashMap<>(resolvedStepInput(
            step, request, completed == null ? Map.of() : completed));
        resolved.remove(CONTEXT_PARAMETER_RECOVERY_KEY);
        resolved.remove(MODEL_RETRIEVAL_GATE_KEY);
        return resolved;
    }

    private Object checkpointToolContract(InterpretationPlan.Step step, ExecutionRequest request) {
        if (step == null || !step.mcpToolAction()) {
            return Map.of("actionType", step == null ? null : step.actionType());
        }
        ToolMetadata metadata = request == null || request.toolRegistry() == null
            ? null : request.toolRegistry().getToolMetadata(step.toolName());
        return mapOf(
            "toolName", step.toolName(),
            "metadata", metadata,
            "allowed", request == null ? List.of() : safeList(request.allowedTools())
        );
    }

    private String checkpointContextFingerprint(ExecutionRequest request,
                                                String fingerprintAttribute,
                                                String objectAttribute,
                                                List<String> fallbackKeys) {
        Map<String, Object> attributes = request == null || request.attributes() == null
            ? Map.of() : request.attributes();
        Object pinnedFingerprint = attributes.get(fingerprintAttribute);
        if (pinnedFingerprint != null && !String.valueOf(pinnedFingerprint).isBlank()) {
            return String.valueOf(pinnedFingerprint).trim();
        }
        Object explicit = attributes.get(objectAttribute);
        if (explicit != null) {
            return sha256(explicit);
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String key : fallbackKeys) {
            if (attributes.containsKey(key)) {
                selected.put(key, attributes.get(key));
            }
        }
        if ("checkpointExecutionEnvironment".equals(objectAttribute)) {
            selected.putIfAbsent("javaVersion", System.getProperty("java.version"));
            selected.putIfAbsent("osName", System.getProperty("os.name"));
            selected.putIfAbsent("osArch", System.getProperty("os.arch"));
        }
        return sha256(selected);
    }

    private String resultFingerprint(StepExecution execution) {
        if (execution == null) {
            return null;
        }
        return sha256(mapOf(
            "stepId", execution.stepId(),
            "actionType", execution.actionType(),
            "toolName", execution.toolName(),
            "output", execution.output(),
            "finalAnswer", execution.finalAnswer()
        ));
    }

    private String sha256(Object value) {
        try {
            Object normalized = normalizeFingerprintValue(value);
            byte[] serialized = RESULT_OBJECT_MAPPER.writeValueAsBytes(normalized);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(serialized);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fingerprint plan checkpoint value", ex);
        }
    }

    private Object normalizeFingerprintValue(Object value) {
        Object generic = RESULT_OBJECT_MAPPER.convertValue(value, Object.class);
        return sortFingerprintValue(generic);
    }

    private Object sortFingerprintValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sortFingerprintValue(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sortFingerprintValue).toList();
        }
        return value;
    }

    private String dependencyFailurePolicy(InterpretationPlan plan, Integer failedStepId) {
        if (plan == null || plan.plan() == null || failedStepId == null
            || plan.plan().dependencyContracts() == null) {
            return "stop";
        }
        List<String> policies = plan.plan().dependencyContracts().stream()
            .filter(Objects::nonNull)
            .filter(contract -> failedStepId.equals(contract.from()))
            .map(InterpretationPlan.DependencyContract::onFailure)
            .filter(Objects::nonNull)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .toList();
        return !policies.isEmpty()
            && policies.stream().allMatch("continue_with_partial_evidence"::equals)
            ? "continue_with_partial_evidence"
            : "stop";
    }

    private ExecutionResult withDiagnosticRun(ExecutionResult result,
                                              ExecutionRequest request,
                                              Set<Integer> remainingStepIds) {
        if (result == null || request == null || request.plan() == null) {
            return result;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        if (!metadata.containsKey("resumeToken") || metadata.get("resumeToken") == null) {
            Map<Integer, InterpretationPlan.Step> recoverySteps = request.plan().steps().stream()
                .filter(step -> step != null && step.id() != null)
                .collect(Collectors.toMap(
                    InterpretationPlan.Step::id, step -> step, (left, ignored) -> left, LinkedHashMap::new));
            CheckpointRecovery recoverable = seedPersistedStepCheckpoints(
                runId(request), request, recoverySteps, new LinkedHashMap<>());
            if (!recoverable.stepIds().isEmpty()) {
                metadata.put("recoveryStatus", "AVAILABLE");
                metadata.put("resumeToken", recoverable.resumeToken());
                metadata.put("resumedPlanStepIds", new ArrayList<>(recoverable.stepIds()));
                metadata.put("recoveryAttemptIds", new ArrayList<>(recoverable.attemptIds()));
            }
        }
        Map<String, Object> governance = dagGovernanceContract(request);
        if (!governance.isEmpty()) {
            metadata.put("dagGovernanceContract", governance);
            metadata.put("dagGovernanceContractId", governance.get("contractId"));
            metadata.put("dagGovernanceContractVersion", governance.get("contractVersion"));
            metadata.put("dagGovernanceContractChecksum", governance.get("checksumSha256"));
        }
        DiagnosticRun diagnosticRun = DiagnosticRun.evaluate(
            request.plan(),
            result.steps(),
            remainingStepIds,
            evidenceIteration(request),
            result.status(),
            result.success()
        );
        if (diagnosticRun == null) {
            return new ExecutionResult(
                result.status(), result.success(), result.approvalRequired(), result.errorMessage(),
                result.finalAnswer(), result.steps(), metadata, result.durationMs()
            );
        }
        metadata.put("diagnosticRun", diagnosticRun);
        metadata.put("diagnosticCoverage", diagnosticRun.coverage());
        metadata.put("diagnosticAssessment", diagnosticRun.assessment());
        metadata.put("diagnosticConfidence", diagnosticRun.confidenceEngine());
        metadata.put("diagnosticState", diagnosticRun.state());
        metadata.put("diagnosticOutcome", diagnosticRun.outcome());
        if (diagnosticRun.failureCode() != null) {
            metadata.put("diagnosticFailureCode", diagnosticRun.failureCode());
        }
        if (diagnosticRun.recoveryAction() != null) {
            metadata.put("diagnosticRecoveryAction", diagnosticRun.recoveryAction());
        }
        return new ExecutionResult(
            result.status(),
            result.success(),
            result.approvalRequired(),
            result.errorMessage(),
            result.finalAnswer(),
            result.steps(),
            metadata,
            result.durationMs()
        );
    }

    private Set<Integer> descendantStepIds(InterpretationPlan plan, Set<Integer> rootStepIds) {
        Set<Integer> descendants = new LinkedHashSet<>();
        if (plan == null || plan.steps() == null || rootStepIds == null || rootStepIds.isEmpty()) {
            return descendants;
        }
        boolean changed;
        do {
            changed = false;
            for (InterpretationPlan.Step step : plan.steps()) {
                if (step == null || step.id() == null || descendants.contains(step.id())
                    || rootStepIds.contains(step.id())) {
                    continue;
                }
                boolean affected = safeIntegerList(step.dependsOn()).stream()
                    .anyMatch(dependency -> rootStepIds.contains(dependency) || descendants.contains(dependency));
                if (affected) {
                    changed |= descendants.add(step.id());
                }
            }
        } while (changed);
        return descendants;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dagGovernanceContract(ExecutionRequest request) {
        if (request == null || request.attributes() == null) {
            return Map.of();
        }
        Object value = request.attributes().get(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE);
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> contract = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                contract.put(String.valueOf(key), item);
            }
        });
        return Map.copyOf(contract);
    }

    private String dagGovernanceContractVersion(ExecutionRequest request) {
        Object version = dagGovernanceContract(request).get("contractVersion");
        return version == null || String.valueOf(version).isBlank()
            ? DagGovernanceContractProvider.INITIAL_VERSION
            : String.valueOf(version);
    }

    private boolean dagGovernanceBoolean(ExecutionRequest request,
                                         String section,
                                         String key,
                                         boolean fallback) {
        Object rules = dagGovernanceContract(request).get("rules");
        Object sectionValue = rules instanceof Map<?, ?> ruleMap ? ruleMap.get(section) : null;
        Object value = sectionValue instanceof Map<?, ?> sectionMap ? sectionMap.get(key) : null;
        return value instanceof Boolean configured ? configured : fallback;
    }

    private Set<Integer> planStepIds(InterpretationPlan plan) {
        if (plan == null) {
            return Set.of();
        }
        return plan.steps().stream()
            .filter(step -> step != null && step.id() != null)
            .map(InterpretationPlan.Step::id)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Integer> readyStepIds(Set<Integer> remaining,
                                       Map<Integer, InterpretationPlan.Step> stepsById,
                                       Set<Integer> completedStepIds) {
        if (remaining == null || remaining.isEmpty() || stepsById == null || stepsById.isEmpty()) {
            return List.of();
        }
        Set<Integer> completedIds = completedStepIds == null ? Set.of() : completedStepIds;
        return remaining.stream()
            .filter(Objects::nonNull)
            .sorted()
            .map(stepsById::get)
            .filter(Objects::nonNull)
            .filter(step -> completedIds.containsAll(safeIntegerList(step.dependsOn())))
            .map(InterpretationPlan.Step::id)
            .toList();
    }

    private SemanticBranch semanticBranch(InterpretationPlan plan, List<Integer> readyStepIds) {
        if (plan == null || plan.plan() == null || readyStepIds == null || readyStepIds.size() < 2) {
            return SemanticBranch.none();
        }
        Set<Integer> ready = new LinkedHashSet<>(readyStepIds);
        if (plan.plan().branchGroups() != null) {
            SemanticBranch declared = plan.plan().branchGroups().stream()
                .filter(Objects::nonNull)
                .filter(group -> group.candidateStepIds() != null)
                .map(group -> new SemanticBranch(
                    group.targetStepId(),
                    group.candidateStepIds().stream()
                        .filter(ready::contains)
                        .distinct()
                        .sorted()
                        .toList(),
                    "exclusive".equalsIgnoreCase(group.mode())
                ))
                .filter(branch -> branch.required() && branch.candidateStepIds().size() > 1)
                .sorted(java.util.Comparator.comparing(SemanticBranch::targetStepId,
                    java.util.Comparator.nullsLast(Integer::compareTo)))
                .findFirst()
                .orElse(null);
            if (declared != null) {
                return declared;
            }
        }
        // Backward compatibility for plans persisted before branch_groups became first-class.
        if (plan.plan().dependencyContracts() == null) {
            return SemanticBranch.none();
        }
        Map<Integer, List<Integer>> optionalSourcesByTarget = new LinkedHashMap<>();
        for (InterpretationPlan.DependencyContract contract : plan.plan().dependencyContracts()) {
            if (contract == null || !Boolean.FALSE.equals(contract.required())
                || contract.condition() == null || contract.condition().isBlank()
                || contract.from() == null || contract.to() == null || !ready.contains(contract.from())) {
                continue;
            }
            optionalSourcesByTarget.computeIfAbsent(contract.to(), ignored -> new ArrayList<>())
                .add(contract.from());
        }
        return optionalSourcesByTarget.entrySet().stream()
            .map(entry -> new SemanticBranch(
                entry.getKey(),
                entry.getValue().stream().distinct().sorted().toList(),
                entry.getValue().stream().distinct().count() > 1
            ))
            .filter(SemanticBranch::required)
            .sorted(java.util.Comparator.comparing(SemanticBranch::targetStepId))
            .findFirst()
            .orElseGet(SemanticBranch::none);
    }

    private DagDecision deterministicReadyDecision(InterpretationPlan plan,
                                                    List<Integer> readyStepIds,
                                                    Map<Integer, InterpretationPlan.Step> stepsById,
                                                    Map<Integer, StepExecution> completed,
                                                    SemanticBranch semanticBranch,
                                                    int decisionCount,
                                                    String executionTraceId) {
        if (readyStepIds == null || readyStepIds.isEmpty() || stepsById == null || stepsById.isEmpty()) {
            return null;
        }
        Set<Integer> semanticCandidates = semanticBranch == null
            ? Set.of() : new LinkedHashSet<>(semanticBranch.candidateStepIds());
        List<Integer> deterministicStepIds = readyStepIds.stream()
            .map(stepsById::get)
            .filter(Objects::nonNull)
            .filter(step -> !semanticCandidates.contains(step.id()))
            .filter(step -> semanticBranch == null || !semanticBranch.required()
                || !Objects.equals(step.id(), semanticBranch.targetStepId()))
            .filter(step -> !step.finalAnswerAction() || readyStepIds.size() == 1)
            .filter(step -> !requiresModelTemplateParameterProtocol(step, completed))
            .map(InterpretationPlan.Step::id)
            .toList();
        if (deterministicStepIds.isEmpty()) {
            return null;
        }
        List<Integer> selected = allowParallel(plan)
            ? deterministicStepIds : List.of(deterministicStepIds.get(0));
        InterpretationPlan.Step onlyStep = selected.size() == 1 ? stepsById.get(selected.get(0)) : null;
        String action = onlyStep != null && onlyStep.finalAnswerAction()
            ? "final_answer"
            : selected.size() > 1 ? "execute_parallel_steps" : "execute_step";
        log.info("InterpretationPlan Java Ready-node scheduling: traceId={}, decisionCount={}, action={}, readyStepIds={}, selectedStepIds={}",
            executionTraceId, decisionCount, action, readyStepIds, selected);
        return new DagDecision(
            InterpretationExecutionProtocol.VERSION,
            action,
            selected,
            "Java Runtime selected Ready node(s) deterministically.",
            null,
            mapOf(
                "runtimeDeterministicScheduling", true,
                "readyStepIds", readyStepIds,
                "decisionCount", decisionCount,
                "executionTraceId", executionTraceId
            )
        );
    }

    /**
     * Gives an evidence-backed recovery action precedence over unrelated downstream
     * work. The decision is derived only from the review contract, published tool
     * availability and a material input change; it contains no capability-specific
     * or business-specific rules.
     */
    private DagDecision deterministicEvidenceRecoveryDecision(
        InterpretationPlan plan,
        Set<Integer> remaining,
        Map<Integer, InterpretationPlan.Step> stepsById,
        Map<Integer, StepExecution> completed,
        ExecutionRequest request,
        int decisionCount,
        String executionTraceId
    ) {
        if (plan == null || plan.executionPolicy() == null
            || Integer.valueOf(0).equals(plan.executionPolicy().maxRewriteTimes())
            || completed == null || completed.isEmpty()) {
            return null;
        }
        Set<String> remainingTools = remaining == null ? Set.of() : remaining.stream()
            .map(stepsById::get)
            .filter(Objects::nonNull)
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .map(this::toolSemanticKey)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> allowedTools = safeList(request == null ? null : request.allowedTools()).stream()
            .map(this::toolSemanticKey)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        List<StepExecution> completedInReverseOrder = new ArrayList<>(completed.values());
        java.util.Collections.reverse(completedInReverseOrder);
        Set<String> laterCompletedTools = new LinkedHashSet<>();
        for (StepExecution execution : completedInReverseOrder) {
            if (execution == null) {
                continue;
            }
            Map<String, Object> metadata = execution.metadata() == null
                ? Map.of() : execution.metadata();
            Map<String, Object> evaluation = asStringMap(metadata.get("evidenceEvaluation"));
            boolean expansionRequested = Boolean.TRUE.equals(booleanValue(firstPresent(
                metadata, "shouldExpandQuery", "should_expand_query")))
                || Boolean.TRUE.equals(booleanValue(firstPresent(
                    evaluation, "shouldExpandQuery", "should_expand_query")));
            if (!execution.success() || !expansionRequested
                || !(metadata.get("nextActions") instanceof Iterable<?> actions)) {
                laterCompletedTools.add(toolSemanticKey(execution.toolName()));
                continue;
            }
            for (Object item : actions) {
                Map<String, Object> action = asStringMap(item);
                String requestedTool = stringValue(firstPresent(
                    action, "tool", "toolName", "tool_name"));
                String semanticTool = toolSemanticKey(requestedTool);
                Map<String, Object> inputChanges = asStringMap(firstPresent(
                    action, "input_changes", "inputChanges",
                    "retry_input_changes", "retryInputChanges"));
                if (semanticTool.isBlank() || inputChanges.isEmpty()
                    || laterCompletedTools.contains(semanticTool)
                    || !validRecoveryActionContract(action, request, execution)
                    || (!allowedTools.isEmpty() && !allowedTools.contains(semanticTool))
                    || remainingTools.contains(semanticTool)) {
                    continue;
                }
                log.info("InterpretationPlan prioritized evidence recovery before downstream execution: "
                        + "traceId={}, decisionCount={}, sourceStepId={}, recoveryTool={}, remainingStepIds={}",
                    executionTraceId, decisionCount, execution.stepId(), requestedTool,
                    remaining == null ? List.of() : new ArrayList<>(remaining));
                return new DagDecision(
                    InterpretationExecutionProtocol.VERSION,
                    "rewrite_plan",
                    List.of(),
                    "A completed partial-evidence step supplied an available recovery tool with a material input revision; "
                        + "Runtime must replan before executing unrelated downstream steps.",
                    null,
                    mapOf(
                        "runtimeDeterministicEvidenceRecovery", true,
                        "sourceStepId", execution.stepId(),
                        "recoveryTool", requestedTool,
                        "inputChanges", inputChanges,
                        "decisionCount", decisionCount,
                        "executionTraceId", executionTraceId
                    )
                );
            }
            laterCompletedTools.add(toolSemanticKey(execution.toolName()));
        }
        return null;
    }

    private List<StepExecution> executeWave(List<InterpretationPlan.Step> ready,
                                            ExecutionRequest request,
                                            Map<Integer, StepExecution> completed,
                                            String executionEpoch) {
        if (ready.size() == 1 || !allowParallel(request.plan())) {
            return ready.stream()
                .map(step -> executeStep(step, request, completed, executionEpoch))
                .toList();
        }
        List<CompletableFuture<StepExecution>> futures = ready.stream()
            .map(step -> CompletableFuture.supplyAsync(() -> executeStep(step, request, completed, executionEpoch)))
            .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private StepExecution executeStep(InterpretationPlan.Step step,
                                      ExecutionRequest request,
                                      Map<Integer, StepExecution> completed,
                                      String executionEpoch) {
        if (nodeAttemptStore == null) {
            return executeStepBody(step, request, completed);
        }
        NodeAttemptStore.AttemptSnapshot attempt = nodeAttemptStore.create(
            new NodeAttemptStore.AttemptCommand(
                request.tenantId(), runId(request), executionTraceId(request), request.plan().version(),
                step.id(), stepFingerprint(step), sha256(step.input()), Map.of(
                    "actionType", firstText(step.actionType(), "unknown"),
                    "toolName", firstText(step.toolName(), ""),
                    "executionEpoch", executionEpoch
                )
            )
        );
        try {
            attempt = transitionAttempt(attempt, NodeAttemptStore.State.READY, "dependencies committed", Map.of());
            NodeAttemptStore.LeaseSnapshot lease = null;
            ScheduledFuture<?> heartbeat = null;
            if (nodeAttemptStore.supportsLeases()) {
                String workerId = request.attributes() == null
                    ? RUNTIME_WORKER_ID : firstText(stringValue(request.attributes().get("workerId")), RUNTIME_WORKER_ID);
                long leaseMs = longAttribute(request.attributes(), "nodeLeaseMs", DEFAULT_NODE_LEASE_MS);
                lease = nodeAttemptStore.acquireLease(request.tenantId(), attempt.attemptId(), workerId,
                    Instant.now(), leaseMs);
                NodeAttemptStore.LeaseSnapshot ownedLease = lease;
                heartbeat = LEASE_HEARTBEATS.scheduleAtFixedRate(() -> {
                    try {
                        nodeAttemptStore.heartbeat(request.tenantId(), ownedLease.attemptId(), ownedLease.workerId(),
                            ownedLease.leaseToken(), Instant.now(), leaseMs);
                    } catch (RuntimeException ex) {
                        log.warn("DAG node lease heartbeat rejected. attemptId={} workerId={} error={}",
                            ownedLease.attemptId(), ownedLease.workerId(), ex.getMessage());
                    }
                }, Math.max(250L, leaseMs / 3), Math.max(250L, leaseMs / 3), TimeUnit.MILLISECONDS);
            }
            attempt = transitionAttempt(attempt, NodeAttemptStore.State.RUNNING, "node execution started", Map.of());
            StepExecution execution;
            try {
                execution = executeStepBody(step, request, completed);
            } finally {
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
            }
            if (!execution.success()) {
                attempt = transitionAttempt(attempt, NodeAttemptStore.State.FAILED,
                    firstText(execution.errorMessage(), "node execution failed"), Map.of());
                return withAttemptMetadata(execution, attempt, NodeAttemptStore.State.FAILED);
            }
            if (lease != null) {
                nodeAttemptStore.heartbeat(request.tenantId(), lease.attemptId(), lease.workerId(),
                    lease.leaseToken(), Instant.now(), longAttribute(request.attributes(), "nodeLeaseMs", DEFAULT_NODE_LEASE_MS));
            }
            attempt = transitionAttempt(attempt, NodeAttemptStore.State.PREPARED,
                "node result validated; awaiting commit barrier", Map.of(
                    "resultFingerprint", resultFingerprint(execution),
                    "executionEpoch", executionEpoch
                ));
            return withAttemptMetadata(execution, attempt, NodeAttemptStore.State.PREPARED);
        } catch (RuntimeException ex) {
            try {
                if (attempt != null && !attempt.state().terminal()) {
                    transitionAttempt(attempt, NodeAttemptStore.State.FAILED,
                        firstText(ex.getMessage(), ex.getClass().getSimpleName()), Map.of());
                }
            } catch (RuntimeException persistenceFailure) {
                ex.addSuppressed(persistenceFailure);
            }
            return new StepExecution(
                step.id(), step.actionType(), step.toolName(), false, null,
                "NODE_ATTEMPT_PERSISTENCE_FAILED: " + firstText(ex.getMessage(), ex.getClass().getSimpleName()),
                null, null, 0L, Map.of("nodeAttemptState", "FAILED")
            );
        }
    }

    private long longAttribute(Map<String, Object> attributes, String name, long fallback) {
        if (attributes == null || attributes.get(name) == null) {
            return fallback;
        }
        try {
            return Math.max(1000L, Long.parseLong(String.valueOf(attributes.get(name))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private NodeAttemptStore.AttemptSnapshot transitionAttempt(NodeAttemptStore.AttemptSnapshot attempt,
                                                               NodeAttemptStore.State target,
                                                               String reason,
                                                               Map<String, Object> metadata) {
        return nodeAttemptStore.transition(
            attempt.tenantId(), attempt.attemptId(), attempt.state(), target, reason, metadata);
    }

    private StepExecution withAttemptMetadata(StepExecution execution,
                                              NodeAttemptStore.AttemptSnapshot attempt,
                                              NodeAttemptStore.State state) {
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        metadata.put("nodeAttemptId", attempt.attemptId());
        metadata.put("nodeAttemptNumber", attempt.attemptNumber());
        metadata.put("nodeAttemptState", state.name());
        metadata.put("nodeAttemptRevision", attempt.revision());
        return execution.withMetadata(Map.copyOf(metadata), execution.durationMs());
    }

    private List<StepExecution> commitPreparedWave(List<StepExecution> waveResults,
                                                   ExecutionRequest request,
                                                   String executionEpoch) {
        if (nodeAttemptStore == null || waveResults == null || waveResults.isEmpty()) {
            return waveResults;
        }
        List<String> attemptIds = waveResults.stream()
            .map(step -> stringValue(step.metadata().get("nodeAttemptId")))
            .filter(value -> value != null && !value.isBlank())
            .toList();
        if (attemptIds.size() != waveResults.size()) {
            return rejectPreparedWave(waveResults, request, executionEpoch,
                "commit barrier is missing a required Attempt identity");
        }
        try {
            NodeAttemptStore.BarrierResult barrier = nodeAttemptStore.commitBarrier(
                new NodeAttemptStore.BarrierCommand(
                    request.tenantId(), runId(request), executionEpoch, attemptIds,
                    Map.of("requiredNodeCount", waveResults.size())
                )
            );
            if (barrier == null || !barrier.committed() || barrier.attempts().size() != waveResults.size()) {
                throw new IllegalStateException("Commit barrier did not commit every required node");
            }
            Map<String, NodeAttemptStore.AttemptSnapshot> committed = barrier.attempts().stream()
                .collect(Collectors.toMap(NodeAttemptStore.AttemptSnapshot::attemptId, value -> value));
            return waveResults.stream().map(execution -> {
                String attemptId = stringValue(execution.metadata().get("nodeAttemptId"));
                NodeAttemptStore.AttemptSnapshot snapshot = committed.get(attemptId);
                if (snapshot == null || snapshot.state() != NodeAttemptStore.State.COMMITTED) {
                    throw new IllegalStateException("Commit barrier returned an incomplete Attempt set");
                }
                Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
                metadata.put("nodeAttemptState", NodeAttemptStore.State.COMMITTED.name());
                metadata.put("nodeAttemptRevision", snapshot.revision());
                metadata.put("executionEpoch", executionEpoch);
                metadata.put("commitBarrier", "SATISFIED");
                metadata.put("committedEvidence", true);
                return execution.withMetadata(Map.copyOf(metadata), execution.durationMs());
            }).toList();
        } catch (RuntimeException ex) {
            log.error("DAG commit barrier failed. traceId={} epoch={} attemptIds={} error={}",
                executionTraceId(request), executionEpoch, attemptIds, ex.getMessage());
            return rejectPreparedWave(waveResults, request, executionEpoch,
                "commit barrier failed: " + firstText(ex.getMessage(), ex.getClass().getSimpleName()));
        }
    }

    private List<StepExecution> rejectPreparedWave(List<StepExecution> waveResults,
                                                   ExecutionRequest request,
                                                   String executionEpoch,
                                                   String reason) {
        if (waveResults == null || waveResults.isEmpty()) {
            return List.of();
        }
        return waveResults.stream().map(execution -> {
            if (!execution.success() || nodeAttemptStore == null
                || !NodeAttemptStore.State.PREPARED.name().equals(
                    stringValue(execution.metadata().get("nodeAttemptState")))) {
                return execution;
            }
            String attemptId = stringValue(execution.metadata().get("nodeAttemptId"));
            long revision = longValue(execution.metadata().get("nodeAttemptRevision"), 0L);
            try {
                NodeAttemptStore.AttemptSnapshot failed = nodeAttemptStore.transition(
                    request.tenantId(), attemptId, NodeAttemptStore.State.PREPARED,
                    NodeAttemptStore.State.FAILED, reason, Map.of(
                        "executionEpoch", executionEpoch,
                        "commitBarrier", "REJECTED"
                    ));
                revision = failed.revision();
            } catch (RuntimeException ex) {
                log.error("Failed to reject prepared node Attempt. attemptId={} epoch={} error={}",
                    attemptId, executionEpoch, ex.getMessage());
            }
            Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
            metadata.put("nodeAttemptState", NodeAttemptStore.State.FAILED.name());
            metadata.put("nodeAttemptRevision", revision);
            metadata.put("executionEpoch", executionEpoch);
            metadata.put("commitBarrier", "REJECTED");
            metadata.put("committedEvidence", false);
            metadata.put("discardedResultFingerprint", resultFingerprint(execution));
            return new StepExecution(
                execution.stepId(), execution.actionType(), execution.toolName(), false,
                null, "COMMIT_BARRIER_REJECTED: " + reason, null, null,
                execution.durationMs(), Map.copyOf(metadata)
            );
        }).toList();
    }

    private void replaceWaveResults(List<StepExecution> executions,
                                    Map<Integer, StepExecution> completed,
                                    List<StepExecution> waveResults) {
        if (waveResults == null) {
            return;
        }
        for (StepExecution replacement : waveResults) {
            completed.put(replacement.stepId(), replacement);
            for (int index = executions.size() - 1; index >= 0; index--) {
                if (Objects.equals(executions.get(index).stepId(), replacement.stepId())) {
                    executions.set(index, replacement);
                    break;
                }
            }
        }
    }

    private StepExecution executeStepBody(InterpretationPlan.Step step,
                                          ExecutionRequest request,
                                          Map<Integer, StepExecution> completed) {
        long startedAt = System.currentTimeMillis();
        recordPlanStep(request, step, completed);
        if (step.mcpToolAction()) {
            try {
                Map<String, Object> resolvedInput = resolvedStepInput(step, request, completed);
                Map<String, Object> contextParameterRecovery = new LinkedHashMap<>(
                    asStringMap(resolvedInput.remove(CONTEXT_PARAMETER_RECOVERY_KEY)));
                boolean templateCompletenessRepairApplied = false;
                List<String> templateCompletenessRepairIds = List.of();
                List<Map<String, Object>> templatePreflightTerminalRepairs = List.of();
                Map<String, Object> retrievalGate = new LinkedHashMap<>(
                    asStringMap(resolvedInput.remove(MODEL_RETRIEVAL_GATE_KEY))
                );
                McpToolRouter.RoutingDecision routingDecision = mcpToolRouter.route(
                    step.toolName(),
                    resolvedInput,
                    safeList(request.allowedTools()),
                    request.tenantId(),
                    List.of()
                );
                if (routingDecision.routed() && !routingDecision.allowed()) {
                    throw new IllegalStateException(routingDecision.errorCode() + ": " + routingDecision.reason());
                }
                String executionToolName = routingDecision.routed() && routingDecision.resolvedToolName() != null
                    ? routingDecision.resolvedToolName()
                    : step.toolName();
                List<String> allowedTools = new ArrayList<>(safeList(request.allowedTools()));
                TemplateExecutorInvocation templateInvocation = diagnosticBatchInvocation(
                    step, request.plan(), completed, resolvedInput, allowedTools, request.toolRegistry()
                );
                if (templateInvocation == null) {
                    templateInvocation = reviewedTemplateBatchInvocation(
                        step, completed, resolvedInput, allowedTools, request.toolRegistry()
                    );
                }
                if (templateInvocation == null) {
                    templateInvocation = templateExecutorInvocation(
                        step,
                        completed,
                        resolvedInput,
                        allowedTools
                    );
                }
                if (templateInvocation != null) {
                    executionToolName = templateInvocation.toolName();
                    resolvedInput = templateInvocation.arguments();
                    retrievalGate = Map.of();
                    if (batchToolInput(resolvedInput)) {
                        templateCompletenessRepairIds = reviewedSelectedTemplateIds(completed);
                        templateCompletenessRepairApplied = templateCompletenessRepairIds.size() >= 2
                            && !declaresBatchTransport(step.input());
                        bridgeBatchTemplateInvocations(step, request, completed, resolvedInput);
                        Object rawBatchCalls = resolvedInput.get("calls");
                        if (rawBatchCalls instanceof List<?> batchCalls) {
                            templatePreflightTerminalRepairs = batchCalls.stream()
                                .filter(Map.class::isInstance)
                                .map(Map.class::cast)
                                .filter(call -> call.get("preflightErrorCode") != null)
                                .map(call -> Map.<String, Object>of(
                                    "templateId", firstText(
                                        stringValue(call.get("callId")), "unknown"),
                                    "terminalStatus", "BLOCKED",
                                    "errorCode", String.valueOf(call.get("preflightErrorCode")),
                                    "reason", firstText(
                                        stringValue(call.get("preflightMessage")),
                                        "Runtime preflight blocked unsafe invocation")
                                ))
                                .toList();
                        }
                    }
                }
                assertNoUnresolvedBindingPlaceholders(resolvedInput);
                log.info("InterpretationPlan step resolved input: traceId={}, stepId={}, tool={}, input={}",
                    executionTraceId(request),
                    step.id(),
                    executionToolName,
                    summarize(resolvedInput));
                Map<String, Object> stepAttributes = new LinkedHashMap<>(
                    attributesForStep(request, step, completed, resolvedInput, routingDecision));
                if (!templatePreflightTerminalRepairs.isEmpty()) {
                    stepAttributes.put("runtimeOwnedTemplatePreflight", true);
                }
                ToolRuntimeExecution execution = toolRuntimeService.execute(ToolRuntimeRequest.builder()
                    .toolName(executionToolName)
                    .runtimeMode("interpretation_plan")
                    .requestId(request.requestId())
                    .conversationId(request.conversationId())
                    .tenantId(request.tenantId())
                    .userId(request.userId())
                    .allowedTools(allowedTools)
                    .toolInput(ToolInput.builder()
                        .requestId(request.requestId())
                        .conversationId(request.conversationId())
                        .userId(request.userId())
                        .parameters(resolvedInput)
                        .build())
                    .attributes(stepAttributes)
                    .build());
                RetrievalQualityGate.Evaluation enhancedQuality = retrievalGate.isEmpty()
                    ? null
                    : RetrievalQualityGate.evaluate(
                        execution == null ? null : execution.output(),
                        retrievalGate
                    );
                RetrievalQualityGate.Evaluation originalQuality = null;
                boolean originalSelected = false;
                if (enhancedQuality != null && !enhancedQuality.sufficient()) {
                    Map<String, Object> originalInput = restoreOriginalRetrievalArguments(
                        resolvedInput, retrievalGate
                    );
                    if (equivalentTemplateRetrievalRequest(executionToolName, resolvedInput, originalInput)) {
                        originalQuality = enhancedQuality;
                        log.info("Retrieval quality gate skipped duplicate fallback traceId={} stepId={} tool={}",
                            executionTraceId(request), step.id(), executionToolName);
                    } else {
                        ToolRuntimeExecution originalExecution = toolRuntimeService.execute(ToolRuntimeRequest.builder()
                            .toolName(executionToolName)
                            .runtimeMode("interpretation_plan_retrieval_gate_fallback")
                            .requestId(request.requestId())
                            .conversationId(request.conversationId())
                            .tenantId(request.tenantId())
                            .userId(request.userId())
                            .allowedTools(allowedTools)
                            .toolInput(ToolInput.builder()
                                .requestId(request.requestId())
                                .conversationId(request.conversationId())
                                .userId(request.userId())
                                .parameters(originalInput)
                                .build())
                            .attributes(attributesForStep(request, step, completed, originalInput, routingDecision))
                            .build());
                        originalQuality = RetrievalQualityGate.evaluate(
                            originalExecution == null ? null : originalExecution.output(),
                            retrievalGate
                        );
                        originalSelected = RetrievalQualityGate.preferFallback(enhancedQuality, originalQuality);
                        if (originalSelected) {
                            execution = originalExecution;
                            resolvedInput = originalInput;
                        }
                    }
                    log.info("Retrieval quality gate evaluated traceId={} stepId={} tool={} enhancedCount={} originalCount={} selected={}",
                        executionTraceId(request), step.id(), executionToolName,
                        enhancedQuality.resultCount(), originalQuality.resultCount(),
                        originalSelected ? "original" : "enhanced");
                }
                boolean success = execution != null && execution.output() != null && execution.output().isSuccess();
                log.info("InterpretationPlan step tool completed: traceId={}, stepId={}, tool={}, success={}, durationMs={}, error={}, output={}",
                    executionTraceId(request),
                    step.id(),
                    executionToolName,
                    success,
                    elapsed(startedAt),
                    execution == null || execution.output() == null ? null : execution.output().getErrorMessage(),
                    ToolLogSummarizer.summarizeResult(executionToolName,
                        execution == null || execution.output() == null ? null : execution.output().getData()));
                Object rawOutput = execution == null || execution.output() == null
                    ? null
                    : execution.output().getData();
                Object normalizedOutput = DIAGNOSTIC_EVIDENCE_NORMALIZER.normalize(rawOutput);
                Map<String, Object> stepMetadata = new LinkedHashMap<>();
                stepMetadata.put("resolvedInput", new LinkedHashMap<>(resolvedInput));
                if (templateCompletenessRepairApplied) {
                    Map<String, Object> repairEvent = Map.of(
                        "contractVersion", dagGovernanceContractVersion(request),
                        "eventKind", "DAG_REPAIR",
                        "eventState", "APPLIED",
                        "repairCode", "TEMPLATE_SET_COMPLETENESS_RESTORED",
                        "stepId", step.id(),
                        "admittedTemplateIds", templateCompletenessRepairIds,
                        "compiledCallCount", templateCompletenessRepairIds.size(),
                        "stopOnFailure", false
                    );
                    stepMetadata.put("eventKind", "DAG_REPAIR");
                    stepMetadata.put("eventState", "APPLIED");
                    stepMetadata.put("repairAttempt", 1);
                    stepMetadata.put("repairEvent", repairEvent);
                    stepMetadata.put("runtimeTemplateCompletenessRepair", repairEvent);
                }
                if (!templatePreflightTerminalRepairs.isEmpty()) {
                    Map<String, Object> repairEvent = Map.of(
                        "contractVersion", dagGovernanceContractVersion(request),
                        "eventKind", "DAG_REPAIR",
                        "eventState", "APPLIED",
                        "repairCode", "TEMPLATE_BATCH_TERMINAL_COVERAGE_APPLIED",
                        "stepId", step.id(),
                        "blockedTemplateCount", templatePreflightTerminalRepairs.size(),
                        "blockedTemplates", templatePreflightTerminalRepairs,
                        "remainingCallsContinued", true
                    );
                    stepMetadata.put("eventKind", "DAG_REPAIR");
                    stepMetadata.put("eventState", "APPLIED");
                    stepMetadata.put("repairAttempt", 1);
                    stepMetadata.put("repairEvent", repairEvent);
                    stepMetadata.put("runtimeTemplatePreflightRepairs",
                        templatePreflightTerminalRepairs);
                }
                if (!contextParameterRecovery.isEmpty()) {
                    Map<String, Object> repairEvent = new LinkedHashMap<>(contextParameterRecovery);
                    repairEvent.put("contractVersion", dagGovernanceContractVersion(request));
                    repairEvent.put("eventKind", "DAG_REPAIR");
                    repairEvent.put("eventState", "APPLIED");
                    repairEvent.put("repairCode", "CONTEXT_PARAMETER_EVIDENCE_APPLIED");
                    repairEvent.put("stepId", step.id());
                    stepMetadata.put("eventKind", "DAG_REPAIR");
                    stepMetadata.put("eventState", "APPLIED");
                    stepMetadata.put("repairAttempt", 1);
                    stepMetadata.put("repairEvent", Map.copyOf(repairEvent));
                    stepMetadata.put("contextParameterRecovery", Map.copyOf(contextParameterRecovery));
                }
                if (enhancedQuality != null) {
                    stepMetadata.put("retrievalQualityGate", RetrievalQualityGate.report(
                        enhancedQuality, originalQuality, originalSelected
                    ));
                }
                if (normalizedOutput != rawOutput) {
                    stepMetadata.put("diagnosticEvidenceNormalized", true);
                    stepMetadata.put("diagnosticEvidenceContractVersion",
                        DiagnosticEvidenceNormalizer.CONTRACT_VERSION);
                    stepMetadata.put("rawOutputType", rawOutput.getClass().getSimpleName());
                }
                StepExecution result = new StepExecution(
                    step.id(),
                    step.actionType(),
                    executionToolName,
                    success,
                    normalizedOutput,
                    execution == null || execution.output() == null ? "Tool returned no execution" : execution.output().getErrorMessage(),
                    execution,
                    null,
                    elapsed(startedAt),
                    Map.copyOf(stepMetadata)
                );
                if (result.success()) {
                    result = reviewToolResult(request, step, result, completed, startedAt);
                }
                result = validateStepOutput(request.plan(), step, result, completed, request);
                recordPlanObservation(request, result, execution == null ? null : execution.output());
                return result;
            } catch (RuntimeException ex) {
                log.warn("InterpretationPlan step failed before tool execution: traceId={}, stepId={}, tool={}, error={}",
                    executionTraceId(request),
                    step.id(),
                    step.toolName(),
                    ex.getMessage());
                StepExecution result = new StepExecution(
                    step.id(),
                    step.actionType(),
                    step.toolName(),
                    false,
                    null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    null,
                    null,
                    elapsed(startedAt)
                );
                recordPlanObservation(request, result, null);
                return result;
            }
        }
        if (step.finalAnswerAction()) {
            StepExecution result = new StepExecution(
                step.id(),
                step.actionType(),
                step.toolName(),
                true,
                step.input(),
                null,
                null,
                stringValue(firstPresent(step.input(), "answer", "response", "text", "result")),
                elapsed(startedAt)
            );
            result = validateStepOutput(request.plan(), step, result, completed, request);
            recordPlanObservation(request, result, null);
            return result;
        }
        StepExecution result = new StepExecution(
            step.id(),
            step.actionType(),
            step.toolName(),
            true,
            step.input(),
            null,
            null,
            null,
            elapsed(startedAt)
        );
        result = validateStepOutput(request.plan(), step, result, completed, request);
        recordPlanObservation(request, result, null);
        return result;
    }

    private StepExecution validateStepOutput(InterpretationPlan plan,
                                             InterpretationPlan.Step step,
                                             StepExecution execution,
                                             Map<Integer, StepExecution> completed,
                                             ExecutionRequest request) {
        if (step == null || execution == null || !execution.success()) {
            return execution;
        }
        List<String> violations = new ArrayList<>();
        InterpretationPlan.OutputContract outputContract = step.outputContract();
        if (outputContract != null && outputContract.type() != null && !outputContract.type().isBlank()) {
            String expectedType = outputContract.type().trim().toLowerCase(Locale.ROOT);
            if (!matchesOutputContractType(expectedType, execution.output())) {
                violations.add("output_contract expected " + expectedType + " but was "
                    + outputTypeName(execution.output()));
            }
        }
        InterpretationPlan.Validation validation = step.validation();
        boolean required = validation != null && Boolean.TRUE.equals(validation.required());
        String rule = validation == null || validation.rule() == null
            ? ""
            : validation.rule().trim().toLowerCase(Locale.ROOT);
        if (required && execution.output() == null) {
            violations.add("validation.required output is missing");
        }
        if ("non_empty".equals(rule) && isEmptyOutput(execution.output())) {
            violations.add("validation.non_empty output is empty");
        }
        if ("confidence_threshold".equals(rule)) {
            Double actual = outputConfidence(execution.output());
            double threshold = validation.threshold() == null ? 0.0 : validation.threshold();
            if (actual == null || actual < threshold) {
                violations.add("validation.confidence_threshold expected >= " + threshold
                    + " but was " + (actual == null ? "missing" : actual));
            }
        }
        List<Map<String, Object>> deterministicContractRepairs = new ArrayList<>();
        if (plan != null && plan.plan() != null && plan.plan().edgeContracts() != null) {
            Map<Integer, StepExecution> validationState = new LinkedHashMap<>(
                completed == null ? Map.of() : completed);
            if (step.id() != null) {
                validationState.put(step.id(), execution);
            }
            for (InterpretationPlan.EdgeContract contract : plan.plan().edgeContracts()) {
                if (contract == null || !Objects.equals(step.id(), contract.from())) {
                    continue;
                }
                if (indexedEdgeTargetsFinalAnswer(plan, contract)) {
                    continue;
                }
                if (runtimeOwnsDiagnosticTemplateTransport(
                    plan, contract.from(), contract.to(), contract.field(), validationState
                )) {
                    continue;
                }
                Object outputValue = contractValue(execution, contract.field());
                ContractCheck check = checkContract(contract, execution, request);
                if (!check.success()) {
                    violations.add(check.message());
                } else if (outputValue == null && environmentContractField(contract.field())
                    && environmentContractValue(execution, request, contract.from()) != null) {
                    deterministicContractRepairs.add(Map.of(
                        "repairCode", "AGENT_ENVIRONMENT_CONTEXT_APPLIED",
                        "field", contract.field(),
                        "value", environmentContractValue(execution, request, contract.from()),
                        "sourceStepId", contract.from(),
                        "targetStepId", contract.to()
                    ));
                }
            }
        }
        if (violations.isEmpty()) {
            if (!deterministicContractRepairs.isEmpty()) {
                Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
                Map<String, Object> repairEvent = Map.of(
                    "contractVersion", dagGovernanceContractVersion(request),
                    "eventKind", "DAG_REPAIR",
                    "eventState", "APPLIED",
                    "repairCode", "AGENT_ENVIRONMENT_CONTEXT_APPLIED",
                    "stepId", step.id(),
                    "repairs", List.copyOf(deterministicContractRepairs)
                );
                metadata.put("eventKind", "DAG_REPAIR");
                metadata.put("eventState", "APPLIED");
                metadata.put("repairAttempt", 1);
                metadata.put("repairEvent", repairEvent);
                metadata.put("deterministicContractRepairs", List.copyOf(deterministicContractRepairs));
                return execution.withMetadata(metadata, execution.durationMs());
            }
            return execution;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(
            execution.metadata() == null ? Map.of() : execution.metadata()
        );
        metadata.put("outputContractValidated", true);
        metadata.put("outputContractSatisfied", false);
        metadata.put("outputContractViolations", List.copyOf(violations));
        metadata.put("repairable", true);
        metadata.put("repairAction",
            DiagnosticRunStateMachine.RecoveryAction.REWRITE_PLAN.wireValue());
        return new StepExecution(
            execution.stepId(),
            execution.actionType(),
            execution.toolName(),
            false,
            execution.output(),
            DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED.message(
                String.join("; ", violations)),
            execution.toolExecution(),
            execution.finalAnswer(),
            execution.durationMs(),
            Map.copyOf(metadata)
        );
    }

    private boolean matchesOutputContractType(String expectedType, Object output) {
        if (output == null) {
            return false;
        }
        return switch (expectedType) {
            case "object", "map" -> output instanceof Map<?, ?>;
            case "array", "list" -> output instanceof Iterable<?> || output.getClass().isArray();
            case "string" -> output instanceof CharSequence || output instanceof Character;
            case "number" -> output instanceof Number;
            case "integer" -> output instanceof Byte
                || output instanceof Short
                || output instanceof Integer
                || output instanceof Long
                || output instanceof java.math.BigInteger;
            case "boolean" -> output instanceof Boolean;
            case "json" -> output instanceof Map<?, ?> || output instanceof List<?>;
            // Structured tool results are rendered as JSON text when they are supplied to
            // the model. Keep the original value for evidence/citation extraction, but
            // treat JSON-compatible containers as satisfying a textual consumption
            // contract instead of rejecting successful MCP calls solely by Java type.
            case "text" -> output instanceof CharSequence
                || output instanceof Map<?, ?>
                || output instanceof Iterable<?>
                || output.getClass().isArray();
            case "table" -> output instanceof Iterable<?> || output instanceof Map<?, ?>
                || output.getClass().isArray();
            case "stream" -> output instanceof Iterable<?>
                || output instanceof java.util.stream.BaseStream<?, ?>;
            default -> false;
        };
    }

    private String outputTypeName(Object output) {
        return output == null ? "missing" : output.getClass().getSimpleName();
    }

    private boolean isEmptyOutput(Object output) {
        if (output == null) {
            return true;
        }
        if (output instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        if (output instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (output instanceof java.util.Collection<?> collection) {
            return collection.isEmpty();
        }
        if (output.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(output) == 0;
        }
        return false;
    }

    private Double outputConfidence(Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                values.put(String.valueOf(key), value);
            }
        });
        Object value = firstPresent(values, "confidence", "score", "probability");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void recordPlanStep(ExecutionRequest request,
                                InterpretationPlan.Step step,
                                Map<Integer, StepExecution> completed) {
        String runId = runId(request);
        if (runStore == null || runId == null || runId.isBlank() || step == null || step.id() == null) {
            return;
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>();
        executionPlan.put("workflow", "interpretation_plan");
        executionPlan.put("protocolVersion", InterpretationExecutionProtocol.VERSION);
        executionPlan.put("executionTraceId", executionTraceId(request));
        executionPlan.put("workflowExecutionAttempt", workflowExecutionAttempt(request));
        executionPlan.put("planExecutionScope", planExecutionScope(request));
        executionPlan.put("evidenceIteration", evidenceIteration(request));
        executionPlan.put("interpretationPlanStepId", step.id());
        executionPlan.put("actionType", step.actionType());
        executionPlan.put("tool", step.toolName());
        executionPlan.put("dependsOn", step.dependsOn() == null ? List.of() : step.dependsOn());
        executionPlan.put("completedPlanStepIds", new ArrayList<>(completed.keySet()));
        runStore.recordStep(runId, AgentRunStep.builder()
            .step(step.id())
            .action(step.actionType())
            .toolName(step.toolName())
            .resolvedToolName(step.toolName())
            .reason("InterpretationPlan step " + step.id())
            .executionPlan(executionPlan)
            .plannedAt(System.currentTimeMillis())
            .observationCount(completed.size())
            .build());
    }

    private void recordPlanObservation(ExecutionRequest request,
                                       StepExecution step,
                                       ToolOutput output) {
        String runId = runId(request);
        if (runStore == null || runId == null || runId.isBlank() || step == null || step.stepId() == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("structuredRuntimeObservation", true);
        metadata.put("workflow", "interpretation_plan");
        metadata.put("protocolVersion", InterpretationExecutionProtocol.VERSION);
        metadata.put("executionTraceId", executionTraceId(request));
        metadata.put("workflowExecutionAttempt", workflowExecutionAttempt(request));
        metadata.put("planExecutionScope", planExecutionScope(request));
        metadata.put("evidenceIteration", evidenceIteration(request));
        metadata.put("lifecyclePhase", "observation");
        metadata.put("interpretationPlanStepId", step.stepId());
        metadata.put("evidenceId", evidenceId(request, step));
        metadata.put("interpretationPlanActionType", step.actionType());
        metadata.put("toolName", step.toolName());
        InterpretationPlan.Step definition = request.plan().steps().stream()
            .filter(candidate -> candidate != null && Objects.equals(candidate.id(), step.stepId()))
            .findFirst()
            .orElse(null);
        if (definition != null) {
            metadata.put("planStepDefinitionFingerprint", stepFingerprint(definition));
        }
        metadata.put("success", step.success());
        metadata.put("durationMs", step.durationMs());
        metadata.put("type", step.success() ? "tool" : "tool_failure");
        if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
            metadata.put("errorMessage", step.errorMessage());
        }
        metadata.putAll(step.metadata() == null ? Map.of() : step.metadata());
        if (step.output() != null) {
            metadata.put("stepOutput", step.output());
        }
        if (output != null && output.getExecutionTimeMs() != null) {
            metadata.put("toolExecutionTimeMs", output.getExecutionTimeMs());
        }
        runStore.recordObservation(runId, AgentObservation.builder()
            .type(step.success() ? "tool" : "tool_failure")
            .source(step.toolName() == null || step.toolName().isBlank() ? step.actionType() : step.toolName())
            .content(planStepObservation(step))
            .metadata(metadata)
            .build());
    }

    private Object workflowExecutionAttempt(ExecutionRequest request) {
        return request == null || request.attributes() == null
            ? 0
            : request.attributes().getOrDefault("workflowExecutionAttempt", 0);
    }

    /** Stable task/plan-attempt key used to isolate rewritten DAG state and evidence. */
    private String planExecutionScope(ExecutionRequest request) {
        String taskId = runId(request);
        if (taskId == null || taskId.isBlank()) {
            taskId = request == null || request.requestId() == null || request.requestId().isBlank()
                ? "unscoped"
                : request.requestId();
        }
        return taskId + "::attempt:" + String.valueOf(workflowExecutionAttempt(request));
    }

    private int evidenceIteration(ExecutionRequest request) {
        Object value = workflowExecutionAttempt(request);
        if (value instanceof Number number) {
            return Math.max(1, number.intValue() + 1);
        }
        String text = String.valueOf(value);
        int separator = text.indexOf('.');
        if (separator > 0) {
            text = text.substring(0, separator);
        }
        try {
            return Math.max(1, Integer.parseInt(text) + 1);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private String evidenceId(ExecutionRequest request, StepExecution step) {
        return "iteration:" + evidenceIteration(request)
            + ":step:" + step.stepId()
            + ":tool:" + (step.toolName() == null ? step.actionType() : step.toolName());
    }

    private void recordStateUpdate(ExecutionRequest request,
                                   Map<Integer, StepExecution> completed,
                                   Set<Integer> remaining,
                                   List<StepExecution> waveResults,
                                   StepExecution failed) {
        String runId = runId(request);
        if (runStore == null || runId == null || runId.isBlank()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("structuredRuntimeObservation", true);
        metadata.put("workflow", "interpretation_plan");
        metadata.put("protocolVersion", InterpretationExecutionProtocol.VERSION);
        metadata.put("executionTraceId", executionTraceId(request));
        metadata.put("workflowExecutionAttempt", workflowExecutionAttempt(request));
        metadata.put("planExecutionScope", planExecutionScope(request));
        metadata.put("lifecyclePhase", "state_update");
        metadata.put("completedPlanStepIds", new ArrayList<>(completed.keySet()));
        metadata.put("remainingPlanStepIds", new ArrayList<>(remaining));
        metadata.put("waveStepIds", waveResults == null ? List.of() : waveResults.stream()
            .map(StepExecution::stepId)
            .toList());
        metadata.put("failedStepId", failed == null ? null : failed.stepId());
        runStore.recordObservation(runId, AgentObservation.builder()
            .type(failed == null ? "state_update" : "state_update_failed")
            .source("interpretation_plan_state")
            .content(failed == null
                ? "InterpretationPlan state updated after completed wave."
                : "InterpretationPlan state updated after failed wave.")
            .metadata(metadata)
            .build());
    }

    private String planStepObservation(StepExecution step) {
        String name = step.toolName() == null || step.toolName().isBlank() ? step.actionType() : step.toolName();
        if (step.success()) {
            return "InterpretationPlan step " + step.stepId() + " " + name + " completed.";
        }
        return "InterpretationPlan step " + step.stepId() + " " + name + " failed: "
            + (step.errorMessage() == null || step.errorMessage().isBlank() ? "unknown error" : step.errorMessage());
    }

    private InterpretationPlanEventState eventState(String runId,
                                                    Set<Integer> fallbackCompletedStepIds,
                                                    ExecutionRequest request) {
        if (runStore == null || runId == null || runId.isBlank()) {
            return new InterpretationPlanEventState(
                fallbackCompletedStepIds == null ? Set.of() : new LinkedHashSet<>(fallbackCompletedStepIds),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
            );
        }
        return InterpretationPlanEventState.from(
            runStore.events(runId), fallbackCompletedStepIds,
            workflowExecutionAttempt(request), planExecutionScope(request));
    }

    private Map<String, Object> attributesForStep(ExecutionRequest request,
                                                  InterpretationPlan.Step step,
                                                  Map<Integer, StepExecution> completed,
                                                  Map<String, Object> resolvedInput,
                                                  McpToolRouter.RoutingDecision routingDecision) {
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes() == null ? Map.of() : request.attributes());
        attributes.put("interpretationPlanVersion", request.plan().version());
        attributes.put("interpretationPlanStepId", step.id());
        attributes.put("interpretationPlanActionType", step.actionType());
        attributes.put("planExecutionScope", planExecutionScope(request));
        Map<String, Object> executionPlan = new LinkedHashMap<>();
        executionPlan.put("workflow", "interpretation_plan");
        executionPlan.put("intent", request.plan().intent() == null ? "" : request.plan().intent().goal());
        executionPlan.put("tool", routingDecision != null && routingDecision.resolvedToolName() != null
            ? routingDecision.resolvedToolName()
            : step.toolName());
        executionPlan.put("requestedTool", step.toolName());
        executionPlan.put("risk_level", request.plan().intent() == null ? "low" : request.plan().intent().riskLevel());
        executionPlan.put("parameters", resolvedInput == null ? Map.of() : resolvedInput);
        executionPlan.put("reason", "InterpretationPlan step " + step.id());
        if (routingDecision != null && routingDecision.routed()) {
            executionPlan.put("toolRouter", routingDecision.metadata());
            attributes.put("toolRouterDecision", routingDecision.metadata());
        }
        attributes.put("executionPlan", executionPlan);
        appendWorkflowTargetContinuity(attributes, request.plan(), step, completed);
        appendDiagnosticBatchAttributes(attributes, request.plan(), step, resolvedInput);
        attributes.put("completedPlanStepIds", new ArrayList<>(completed.keySet()));
        Set<String> completedToolSet = new LinkedHashSet<>();
        Object priorCompletedTools = attributes.get("workflowCompletedTools");
        if (priorCompletedTools instanceof List<?> list) {
            list.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(tool -> !tool.isBlank())
                .forEach(completedToolSet::add);
        }
        completed.values().stream()
            .filter(this::terminalToolExecutionSucceeded)
            .map(StepExecution::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .forEach(completedToolSet::add);
        List<String> completedTools = new ArrayList<>(completedToolSet);
        attributes.put("workflowCompletedTools", completedTools);
        attributes.put("completedTools", completedTools);
        return attributes;
    }

    private void appendWorkflowTargetContinuity(Map<String, Object> attributes,
                                                InterpretationPlan plan,
                                                InterpretationPlan.Step step,
                                                Map<Integer, StepExecution> completed) {
        if (attributes == null || plan == null || step == null || completed == null || completed.isEmpty()) {
            return;
        }
        Map<Integer, InterpretationPlan.Step> stepsById = plan.steps().stream()
            .filter(candidate -> candidate != null && candidate.id() != null)
            .collect(Collectors.toMap(
                InterpretationPlan.Step::id,
                candidate -> candidate,
                (left, ignored) -> left,
                LinkedHashMap::new
            ));
        LinkedHashSet<Integer> ancestors = new LinkedHashSet<>();
        collectAncestorStepIds(step, stepsById, ancestors);
        for (Integer ancestorId : ancestors) {
            StepExecution execution = completed.get(ancestorId);
            if (!terminalToolExecutionSucceeded(execution)) {
                continue;
            }
            String targetRef = workflowTargetRef(asStringMap(execution.metadata().get("resolvedInput")));
            if (targetRef == null || targetRef.isBlank()) {
                continue;
            }
            Map<String, Object> workflowContext = new LinkedHashMap<>(
                asStringMap(attributes.get("workflowContext"))
            );
            workflowContext.putIfAbsent("workflowTargetRef", targetRef);
            attributes.put("workflowContext", workflowContext);
            return;
        }
    }

    private void collectAncestorStepIds(InterpretationPlan.Step step,
                                        Map<Integer, InterpretationPlan.Step> stepsById,
                                        LinkedHashSet<Integer> ancestors) {
        for (Integer dependencyId : safeIntegerList(step == null ? null : step.dependsOn())) {
            if (dependencyId == null || ancestors.contains(dependencyId)) {
                continue;
            }
            InterpretationPlan.Step dependency = stepsById.get(dependencyId);
            if (dependency != null) {
                collectAncestorStepIds(dependency, stepsById, ancestors);
            }
            ancestors.add(dependencyId);
        }
    }

    private String workflowTargetRef(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : List.of(
            "workflowTargetRef", "targetAssetId", "targetAssetName",
            "assetId", "assetName", "databaseAssetId", "databaseAssetName"
        )) {
            String value = stringValue(values.get(key));
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        for (String nestedKey : List.of(
            "executionContext", "execution_context", "filters", "target",
            "defaultDataAsset", "mcpExecutionContext", "workflowContext"
        )) {
            String nested = workflowTargetRef(asStringMap(values.get(nestedKey)));
            if (nested != null && !nested.isBlank()) {
                return nested;
            }
        }
        return null;
    }

    private boolean terminalToolExecutionSucceeded(StepExecution execution) {
        if (execution == null) {
            return false;
        }
        if (execution.success()) {
            return true;
        }
        return execution.toolExecution() != null
            && execution.toolExecution().output() != null
            && execution.toolExecution().output().isSuccess();
    }

    private void appendDiagnosticBatchAttributes(
        Map<String, Object> attributes,
        InterpretationPlan plan,
        InterpretationPlan.Step step,
        Map<String, Object> resolvedInput
    ) {
        if (attributes == null || plan == null || plan.plan() == null
            || plan.plan().diagnosticProfile() == null || step == null || step.id() == null
            || !batchToolInput(resolvedInput)) {
            return;
        }
        List<String> declared = (plan.plan().diagnosticProfile().checks() == null
            ? List.<InterpretationPlan.DiagnosticCheck>of()
            : plan.plan().diagnosticProfile().checks()).stream()
            .filter(Objects::nonNull)
            .filter(check -> !Boolean.FALSE.equals(check.required()))
            .filter(check -> check.stepIds() != null && check.stepIds().contains(step.id()))
            .map(InterpretationPlan.DiagnosticCheck::checkId)
            .filter(Objects::nonNull)
            .toList();
        Object rawCalls = firstPresent(resolvedInput, "calls", "toolCalls", "tool_calls");
        List<String> compiled = new ArrayList<>();
        if (rawCalls instanceof Iterable<?> calls) {
            for (Object value : calls) {
                if (value instanceof Map<?, ?> call) {
                    Object id = call.containsKey("callId") ? call.get("callId")
                        : call.containsKey("call_id") ? call.get("call_id")
                        : call.get("id");
                    if (id != null && !String.valueOf(id).isBlank()) {
                        compiled.add(String.valueOf(id));
                    }
                }
            }
        }
        if (!compiled.isEmpty() && !declared.containsAll(compiled)) {
            attributes.remove("diagnosticRunId");
            attributes.remove("diagnosticDeclaredCheckIds");
            attributes.remove("diagnosticDeclaredCheckCount");
            attributes.remove("diagnosticCompiledCallCount");
            attributes.remove("diagnosticMissingAuthorizedCheckIds");
            attributes.put("diagnosticBatchMappingIgnored", true);
            attributes.put("diagnosticBatchMappingReason",
                "Compiled call identifiers are governed template executions rather than diagnostic check identifiers");
            return;
        }
        List<String> missing = declared.stream().filter(id -> !compiled.contains(id)).toList();
        attributes.put("diagnosticRunId", firstText(
            stringValue(attributes.get(AGENT_RUN_ID_ATTRIBUTE)),
            stringValue(attributes.get("__agentRunId"))
        ));
        attributes.put("diagnosticDeclaredCheckIds", declared);
        attributes.put("diagnosticDeclaredCheckCount", declared.size());
        attributes.put("diagnosticCompiledCallCount", compiled.size());
        attributes.put("diagnosticMissingAuthorizedCheckIds", missing);
    }

    private StepExecution reviewToolResult(ExecutionRequest request,
                                           InterpretationPlan.Step step,
                                           StepExecution execution,
                                           Map<Integer, StepExecution> completed,
                                           long startedAt) {
        if (execution != null
            && execution.toolExecution() != null
            && execution.toolExecution().audit() != null
            && Boolean.TRUE.equals(execution.toolExecution().audit().get("batchExecution"))) {
            Map<String, Object> batchMetadata = new LinkedHashMap<>(execution.metadata());
            batchMetadata.put("toolResultReviewSkipped", true);
            batchMetadata.put("toolResultReviewSkipReason",
                "ordered batch children were independently validated and audited by ToolRuntimeService");
            batchMetadata.put("batchExecution", true);
            return execution.withMetadata(batchMetadata, elapsed(startedAt));
        }
        StepExecution evidenceReviewExecution = executionWithResolvedEvidence(execution);
        StepReview localReview = localToolResultReview(step, evidenceReviewExecution);
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        if (evidenceReviewExecution != execution) {
            metadata.put("externalizedEvidenceResolvedForReview", true);
        }
        if (localReview != null) {
            metadata.put("toolResultReviewEnabled", stepResultReviewer != null);
            metadata.put("localDecisionPhase", "fact_check");
            metadata.put("localFactCheckSatisfied", localReview.satisfied());
            metadata.put("localFactCheckReason", localReview.reason());
            metadata.putAll(localReview.metadata() == null ? Map.of() : localReview.metadata());
            if (localReview.satisfied() && stepResultReviewer == null) {
                return execution.withMetadata(metadata, elapsed(startedAt));
            }
            if (localReview.satisfied() && shouldSkipModelReviewAfterLocalFactCheck(request, step, metadata)) {
                metadata.put("toolResultReviewSkipped", true);
                metadata.put("toolResultReviewSkipReason", "deterministic discovery fact check accepted non-empty structured results");
                return execution.withMetadata(metadata, elapsed(startedAt));
            }
            if (localReview.satisfied()) {
                execution = execution.withMetadata(metadata, elapsed(startedAt));
            }
        }
        if (localReview != null && !localReview.satisfied()) {
            return new StepExecution(
                execution.stepId(),
                execution.actionType(),
                execution.toolName(),
                false,
                execution.output(),
                "Tool result rejected by local fact check: " + localReview.reason(),
                execution.toolExecution(),
                execution.finalAnswer(),
                elapsed(startedAt),
                metadata
            );
        }
        if (stepResultReviewer == null) {
            return execution;
        }
        int maxAttempts = toolResultReviewMaxAttempts(request);
        StepReview lastReview = null;
        metadata.put("toolResultReviewEnabled", true);
        metadata.putIfAbsent("localDecisionPhase", "fact_check");
        metadata.put("toolResultReviewMaxAttempts", maxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                lastReview = stepResultReviewer.review(new StepReviewRequest(
                    request.plan(),
                    step,
                    evidenceReviewExecution.withMetadata(metadata, elapsed(startedAt)),
                    Map.copyOf(completed),
                    attempt,
                    maxAttempts,
                    runId(request)
                ));
            } catch (RuntimeException ex) {
                lastReview = StepReview.rejected(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    Map.of("toolResultReviewException", ex.getClass().getName())
                );
            }
            if (lastReview != null) {
                metadata.put("toolResultReviewSatisfied", lastReview.satisfied());
                metadata.put("toolResultReviewReason", lastReview.reason());
                metadata.put("toolResultReviewAttempts", attempt);
                metadata.putAll(lastReview.metadata() == null ? Map.of() : lastReview.metadata());
                if (!lastReview.satisfied() && localReview != null && localReview.satisfied()
                    && Boolean.TRUE.equals(metadata.get("toolResultReviewUnavailable"))
                    && !isSemanticCandidateDiscovery(execution.toolName())) {
                    metadata.put("toolResultReviewSkipped", true);
                    metadata.put("toolResultReviewSatisfied", true);
                    metadata.put("toolResultReviewReason",
                        "Model reviewer was unavailable; preserving the deterministically fact-checked tool result.");
                    return execution.withMetadata(metadata, elapsed(startedAt));
                }
                appendTemplateExecutionReviewContract(execution, lastReview, metadata);
                if (lastReview.satisfied()) {
                    execution = applyRuntimeAssetSelection(execution, lastReview, metadata, startedAt);
                    execution = applyRuntimeTemplateSelection(execution, lastReview, metadata, startedAt);
                    String selectionError = semanticCandidateSelectionError(execution, metadata);
                    if (selectionError != null) {
                        metadata.put("semanticCandidateReviewSatisfied", false);
                        metadata.put("semanticCandidateReviewError", selectionError);
                        return new StepExecution(
                            execution.stepId(), execution.actionType(), execution.toolName(), false,
                            execution.output(), "Tool result rejected by semantic candidate gate: " + selectionError,
                            execution.toolExecution(), execution.finalAnswer(), elapsed(startedAt), metadata
                        );
                    }
                    if (isSemanticCandidateDiscovery(execution.toolName())) {
                        metadata.put("semanticCandidateReviewSatisfied", true);
                    }
                    Map<String, Object> lock = executionLock(step, lastReview);
                    if (!lock.isEmpty()) {
                        metadata.put("executionLock", lock);
                    }
                    return execution.withMetadata(metadata, elapsed(startedAt));
                }
                if (reviewContradictsLocalFacts(lastReview, metadata)
                    && !isSemanticCandidateDiscovery(execution.toolName())) {
                    metadata.put("toolResultReviewContradictedLocalFacts", true);
                    metadata.put("toolResultReviewContradictionReason", lastReview.reason());
                    metadata.put("toolResultReviewSatisfied", true);
                    metadata.put("toolResultReviewReason",
                        "Reviewer rejection contradicted deterministic tool facts; continuing with fact-checked result.");
                    return execution.withMetadata(metadata, elapsed(startedAt));
                }
            }
        }
        String reason = lastReview == null || lastReview.reason() == null || lastReview.reason().isBlank()
            ? "Tool result did not satisfy the plan step after model review."
            : lastReview.reason();
        if (isSemanticCandidateDiscovery(execution.toolName())) {
            if (lastReview != null) {
                execution = applyRuntimeAssetSelection(execution, lastReview, metadata, startedAt);
                execution = applyRuntimeTemplateSelection(execution, lastReview, metadata, startedAt);
            }
            String selectionError = semanticCandidateSelectionError(execution, metadata);
            if (selectionError != null) {
                metadata.put("semanticCandidateReviewSatisfied", false);
                metadata.put("semanticCandidateReviewError", selectionError);
                return new StepExecution(
                    execution.stepId(), execution.actionType(), execution.toolName(), false,
                    execution.output(), "Tool result rejected by semantic candidate review: " + selectionError,
                    execution.toolExecution(), execution.finalAnswer(), elapsed(startedAt), metadata
                );
            }
            // The reviewer may mark the cumulative request incomplete while still
            // explicitly admitting candidates for this discovery step. Preserve that
            // distinction: selected candidates may continue, but the evidence gap stays
            // visible to final synthesis and later retrieval iterations.
            metadata.put("semanticCandidateReviewSatisfied", true);
            metadata.put("toolResultReviewPartialAccepted", true);
            metadata.put("toolResultReviewPartialReason", reason);
            metadata.put("partialEvidence", true);
            metadata.put("evidenceSufficiency", "INSUFFICIENT");
            metadata.put("stepFulfillmentStatus", "PARTIAL");
            return execution.withMetadata(metadata, elapsed(startedAt));
        }
        if (shouldPreservePartialToolResult(execution)
            || reviewRequestsEvidenceRecovery(lastReview)) {
            metadata.put("toolResultReviewPartialAccepted", true);
            metadata.put("toolResultReviewPartialReason", reason);
            metadata.put("toolResultReviewReason",
                "Tool returned structured data and is preserved for final synthesis with limitations: " + reason);
            metadata.put("partialEvidence", true);
            metadata.put("toolExecutionStatus", "SUCCEEDED");
            metadata.put("evidenceSufficiency", "INSUFFICIENT");
            metadata.put("stepFulfillmentStatus", "PARTIAL");
            metadata.put("modelReviewExecutionStatusOverridePrevented", true);
            log.info("InterpretationPlan preserved successful tool execution with partial evidence: "
                    + "traceId={} stepId={} tool={} evidenceSufficiency=INSUFFICIENT reason={}",
                executionTraceId(request), execution.stepId(), execution.toolName(), reason);
            if (isTemplateDiscoveryTool(execution.toolName()) && lastReview != null) {
                execution = applyRuntimeTemplateSelection(execution, lastReview, metadata, startedAt);
            }
            if (isAssetDiscoveryTool(execution.toolName()) && lastReview != null) {
                execution = applyRuntimeAssetSelection(execution, lastReview, metadata, startedAt);
            }
            return execution.withMetadata(metadata, elapsed(startedAt));
        }
        return new StepExecution(
            execution.stepId(),
            execution.actionType(),
            execution.toolName(),
            false,
            execution.output(),
            "Tool result rejected by model review: " + reason,
            execution.toolExecution(),
            execution.finalAnswer(),
            elapsed(startedAt),
            metadata
        );
    }

    private StepExecution executionWithResolvedEvidence(StepExecution execution) {
        if (execution == null || execution.output() == null) {
            return execution;
        }
        if (execution.toolExecution() == null || execution.toolExecution().output() == null) {
            return execution;
        }
        Object resolved = toolRuntimeService.resolveOutputForEvidenceReview(execution.toolExecution().output());
        if (resolved == null || resolved == execution.output()) {
            return execution;
        }
        return new StepExecution(
            execution.stepId(),
            execution.actionType(),
            execution.toolName(),
            execution.success(),
            resolved,
            execution.errorMessage(),
            execution.toolExecution(),
            execution.finalAnswer(),
            execution.durationMs(),
            execution.metadata()
        );
    }

    private void appendTemplateExecutionReviewContract(StepExecution execution,
                                                       StepReview review,
                                                       Map<String, Object> metadata) {
        if (execution == null || review == null || metadata == null
            || !isTemplateExecutionTool(execution.toolName())) {
            return;
        }
        List<String> missingParameters = stringValues(review.metadata().get("missingParameters"));
        Map<String, Object> retryInputChanges = asStringMap(review.metadata().get("retryInputChanges"));
        boolean reselectTemplate = Boolean.TRUE.equals(
            booleanValue(review.metadata().get("templateReselectionRequired")));
        metadata.put("templateExecutionReview", Map.of(
            "schemaVersion", "template_execution_satisfaction.v1",
            "satisfied", review.satisfied(),
            "missingParameters", missingParameters,
            "retryInputChanges", retryInputChanges,
            "templateReselectionRequired", reselectTemplate,
            "retryPolicy", "ONE_REPAIRED_PLAN_EXECUTION",
            "unchangedRetryForbidden", true
        ));
        metadata.put("templateExecutionRetryRequested", !review.satisfied());
        metadata.put("templateExecutionRetryLimit", 1);
        metadata.put("templateExecutionMissingParameters", missingParameters);
        metadata.put("templateExecutionRetryInputChanges", retryInputChanges);
        metadata.put("templateReselectionRequired", reselectTemplate);
    }

    private StepExecution applyRuntimeTemplateSelection(StepExecution execution,
                                                        StepReview review,
                                                        Map<String, Object> metadata,
                                                        long startedAt) {
        if (execution == null || review == null || !isTemplateDiscoveryTool(execution.toolName())) {
            return execution;
        }
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            TEMPLATE_CANDIDATE_EVALUATOR.evaluate(execution.output(), review.metadata());
        metadata.put("runtimeTemplateSelectionApplied", evaluation.applied());
        metadata.put("runtimeTemplateCandidateCount", evaluation.candidateCount());
        metadata.put("runtimeTemplateSelectedCount", evaluation.selectedCount());
        metadata.put("runtimeSelectedTemplateIds", evaluation.selectedIds());
        metadata.put("runtimeTemplateCandidateEvaluations", evaluation.candidateEvaluations());
        metadata.put("runtimeTemplateSelectionReason", evaluation.reason());
        if (!evaluation.applied()) {
            return execution;
        }
        return new StepExecution(
            execution.stepId(),
            execution.actionType(),
            execution.toolName(),
            execution.success(),
            evaluation.output(),
            execution.errorMessage(),
            execution.toolExecution(),
            execution.finalAnswer(),
            elapsed(startedAt),
            metadata
        );
    }

    private boolean isSemanticCandidateDiscovery(String toolName) {
        return isAssetDiscoveryTool(toolName) || isTemplateDiscoveryTool(toolName);
    }

    private String semanticCandidateSelectionError(StepExecution execution,
                                                   Map<String, Object> metadata) {
        if (execution == null || metadata == null) {
            return null;
        }
        if (isAssetDiscoveryTool(execution.toolName())) {
            if (!Boolean.TRUE.equals(metadata.get("runtimeAssetSelectionApplied"))) {
                return "the model did not select an asset id from the returned candidate set";
            }
            Integer selected = integerValue(metadata.get("runtimeAssetSelectedCount"));
            if (selected == null || selected < 1) {
                return "the model selected no usable asset candidate";
            }
        }
        if (isTemplateDiscoveryTool(execution.toolName())) {
            if (!Boolean.TRUE.equals(metadata.get("runtimeTemplateSelectionApplied"))) {
                return "the model did not select a template id from the returned candidate set";
            }
            Integer selected = integerValue(metadata.get("runtimeTemplateSelectedCount"));
            if (selected == null || selected < 1) {
                return "the model selected no usable template candidate";
            }
        }
        return null;
    }


    private boolean requiresModelTemplateParameterProtocol(InterpretationPlan.Step step,
                                                            Map<Integer, StepExecution> completed) {
        if (step == null || !isTemplateExecutionTool(step.toolName()) || completed == null || completed.isEmpty()) {
            return false;
        }
        String templateId = canonicalTemplateId(firstValueAtAnyPath(step.input(),
            "$.templateId", "$.template", "$.template_id"));
        if (templateId == null) {
            templateId = uniqueCompletedTemplateForExecutor(step.toolName(), completed);
        }
        if (templateId == null) {
            templateId = uniqueCompletedTemplateId(completed);
        }
        if (templateId == null) {
            return false;
        }
        Map<String, Object> template = completedTemplateMetadata(completed, templateId);
        return requiredTemplateParameters(template).stream()
            .anyMatch(name -> !templateParameterHasDefault(template, name));
    }

    @SuppressWarnings("unchecked")
    private String uniqueCompletedTemplateId(Map<Integer, StepExecution> completed) {
        Set<String> templateIds = new LinkedHashSet<>();
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isTemplateDiscoveryTool(execution.toolName())) {
                continue;
            }
            for (Object item : templateCandidates(execution.output())) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                String templateId = canonicalTemplateId(new LinkedHashMap<>((Map<String, Object>) raw));
                if (templateId != null) {
                    templateIds.add(templateId);
                }
            }
        }
        return templateIds.size() == 1 ? templateIds.iterator().next() : null;
    }

    @SuppressWarnings("unchecked")
    private List<InterpretationPlan.Step> applyDecisionParameterProtocols(List<InterpretationPlan.Step> selected,
                                                                          DagDecision decision) {
        if (selected == null || selected.isEmpty() || decision == null || decision.metadata() == null) {
            return selected == null ? List.of() : selected;
        }
        Object value = decision.metadata().get("parameterProtocols");
        if (!(value instanceof Iterable<?> protocols)) {
            return selected;
        }
        Map<Integer, Map<String, Object>> byStep = new LinkedHashMap<>();
        for (Object item : protocols) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> protocol = new LinkedHashMap<>((Map<String, Object>) raw);
            Integer stepId = integerValue(firstMapValue(protocol, "step_id", "stepId"));
            if (stepId != null) {
                byStep.put(stepId, protocol);
            }
        }
        return selected.stream().map(step -> {
            Map<String, Object> protocol = step == null ? null : byStep.get(step.id());
            if (protocol == null) {
                return step;
            }
            Map<String, Object> input = new LinkedHashMap<>(step.input() == null ? Map.of() : step.input());
            input.put("parameterProtocol", protocol);
            return new InterpretationPlan.Step(step.id(), step.actionType(), step.toolName(), input,
                step.dependsOn(), step.outputContract(), step.validation());
        }).toList();
    }

    private boolean shouldSkipModelReviewAfterLocalFactCheck(ExecutionRequest request,
                                                              InterpretationPlan.Step step,
                                                              Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        if (!Boolean.TRUE.equals(metadata.get("localFactCheckHasEvidence"))) {
            return false;
        }
        String evidenceType = stringValue(metadata.get("localFactCheckEvidenceType"));
        if ("asset_discovery".equals(evidenceType)) {
            // Structural success only proves that routing candidates exist. It cannot
            // prove that even a single returned asset is the target requested by the
            // user. Semantic admission therefore always belongs to the model reviewer.
            return false;
        }
        if ("template_discovery".equals(evidenceType)) {
            // A template can be structurally executable and still describe the wrong
            // database, capability, or business intent. Never bypass semantic review.
            return false;
        }
        // The local check only accepts a successful, non-empty typed result. Once
        // that contract is satisfied, semantic completeness belongs to the final
        // evidence synthesis. Re-reviewing every accepted result with an LLM turns
        // ordinary evidence gaps into latency and retry loops. Candidate discovery
        // is the sole exception because ambiguous candidates still need selection.
        return evidenceType != null && !evidenceType.isBlank();
    }

    // Retained for compatibility with focused contract tests and legacy reflective
    // diagnostics that only evaluate the local fact-check metadata.
    private boolean shouldSkipModelReviewAfterLocalFactCheck(Map<String, Object> metadata) {
        return shouldSkipModelReviewAfterLocalFactCheck(null, null, metadata);
    }

    private boolean authoritativeWorkflowGoverns(ExecutionRequest request,
                                                   InterpretationPlan.Step step) {
        if (request == null || request.attributes() == null || step == null
            || step.toolName() == null || step.toolName().isBlank()) {
            return false;
        }
        Object rawDag = request.attributes().get("authoritativeWorkflowDag");
        if (!(rawDag instanceof Collection<?> nodes) || nodes.isEmpty()) {
            return false;
        }
        String stepTool = toolSemanticKey(step.toolName());
        for (Object rawNode : nodes) {
            if (!(rawNode instanceof Map<?, ?> node)) {
                continue;
            }
            Object configured = firstMapValue(node, "tool", "toolName");
            if (configured != null && stepTool.equals(toolSemanticKey(String.valueOf(configured)))) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPreservePartialToolResult(StepExecution execution) {
        if (execution == null || !execution.success() || execution.output() == null) {
            return false;
        }
        return hasStructuredToolEvidence(execution.output(), 0);
    }

    private boolean reviewRequestsEvidenceRecovery(StepReview review) {
        if (review == null || review.metadata() == null || review.metadata().isEmpty()) {
            return false;
        }
        Map<String, Object> metadata = review.metadata();
        Map<String, Object> evaluation = asStringMap(metadata.get("evidenceEvaluation"));
        boolean expansionRequested = Boolean.TRUE.equals(booleanValue(firstPresent(
            metadata, "shouldExpandQuery", "should_expand_query")))
            || Boolean.TRUE.equals(booleanValue(firstPresent(
                evaluation, "shouldExpandQuery", "should_expand_query")));
        if (!expansionRequested
            || !(metadata.get("nextActions") instanceof Iterable<?> actions)) {
            return false;
        }
        for (Object item : actions) {
            Map<String, Object> action = asStringMap(item);
            String requestedTool = stringValue(firstPresent(
                action, "tool", "toolName", "tool_name"));
            Map<String, Object> inputChanges = asStringMap(firstPresent(
                action, "input_changes", "inputChanges",
                "retry_input_changes", "retryInputChanges"));
            if (requestedTool != null && !requestedTool.isBlank() && !inputChanges.isEmpty()
                && validRecoveryActionContract(action)) {
                return true;
            }
        }
        return false;
    }

    private boolean validRecoveryActionContract(Map<String, Object> action) {
        if (action == null || action.isEmpty()) {
            return false;
        }
        Map<String, Object> scopeBasis = asStringMap(firstPresent(
            action, "scope_basis", "scopeBasis"));
        Map<String, Object> capabilityBasis = asStringMap(firstPresent(
            action, "capability_basis", "capabilityBasis"));
        List<String> expectedEvidenceTypes = stringValues(firstPresent(
            action, "expected_evidence_types", "expectedEvidenceTypes"));
        String scopeSource = stringValue(firstPresent(scopeBasis, "source"));
        String scopeReference = stringValue(firstPresent(
            scopeBasis, "reference", "quote", "path"));
        String capabilitySource = stringValue(firstPresent(capabilityBasis, "source"));
        String capabilityReference = stringValue(firstPresent(
            capabilityBasis, "reference", "quote", "path"));
        return Set.of("user_query", "tool_result").contains(normalize(scopeSource))
            && scopeReference != null && !scopeReference.isBlank()
            && Set.of("tool_result", "tool_metadata").contains(normalize(capabilitySource))
            && capabilityReference != null && !capabilityReference.isBlank()
            && !expectedEvidenceTypes.isEmpty();
    }

    private boolean validRecoveryActionContract(Map<String, Object> action,
                                                ExecutionRequest request,
                                                StepExecution execution) {
        if (!validRecoveryActionContract(action)) {
            return false;
        }
        Map<String, Object> scopeBasis = asStringMap(firstPresent(
            action, "scope_basis", "scopeBasis"));
        Map<String, Object> capabilityBasis = asStringMap(firstPresent(
            action, "capability_basis", "capabilityBasis"));
        String scopeSource = normalize(stringValue(firstPresent(scopeBasis, "source")));
        String scopeReference = stringValue(firstPresent(
            scopeBasis, "reference", "quote", "path"));
        String capabilitySource = normalize(stringValue(firstPresent(
            capabilityBasis, "source")));
        String capabilityReference = stringValue(firstPresent(
            capabilityBasis, "reference", "quote", "path"));
        boolean scopeGrounded = "user_query".equals(scopeSource)
            ? containsIgnoreCase(originalUserQuery(request), scopeReference)
            : evidenceReferenceExists(execution == null ? null : execution.output(), scopeReference);
        boolean capabilityGrounded;
        if ("tool_result".equals(capabilitySource)) {
            capabilityGrounded = evidenceReferenceExists(
                execution == null ? null : execution.output(), capabilityReference);
        } else {
            String requestedTool = stringValue(firstPresent(
                action, "tool", "toolName", "tool_name"));
            ToolMetadata metadata = request == null || request.toolRegistry() == null
                ? null : request.toolRegistry().getToolMetadata(requestedTool);
            String declaredCapability = metadata == null ? null : String.join(" ",
                safeString(metadata.getId()), safeString(metadata.getTitle()),
                safeString(metadata.getDescription()), safeString(metadata.getCategory()),
                safeString(metadata.getOutputType()), String.valueOf(metadata.getCategories()));
            capabilityGrounded = containsIgnoreCase(declaredCapability, capabilityReference);
        }
        return scopeGrounded && capabilityGrounded;
    }

    private boolean evidenceReferenceExists(Object evidence, String reference) {
        if (evidence == null || reference == null || reference.isBlank()) {
            return false;
        }
        if (reference.startsWith("$.")) {
            return firstValueAtAnyPath(evidence, reference) != null;
        }
        return containsIgnoreCase(String.valueOf(evidence), reference);
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null && expected != null && !expected.isBlank()
            && value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean hasStructuredToolEvidence(Object output, int depth) {
        if (output == null || depth > 8) {
            return false;
        }
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) {
            return hasStructuredToolEvidence(normalized, depth + 1);
        }
        if (output instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (!(output instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        Boolean success = booleanValue(firstMapValue(map, "success"));
        if (Boolean.TRUE.equals(success) && hasAnyMapKey(map,
            "rows", "columns", "results", "resultSets", "result_sets", "payload", "data", "operation", "analysisContext")) {
            return true;
        }
        Integer rowCount = integerValue(firstMapValue(map, "rowCount", "row_count", "resultSetCount", "result_set_count", "statementCount", "statement_count"));
        if (rowCount != null && rowCount > 0) {
            return true;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey() == null
                ? ""
                : String.valueOf(entry.getKey()).replace("_", "").toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            Integer count = integerValue(value);
            if (count != null && count > 0
                && ("count".equals(key) || "size".equals(key) || "total".equals(key)
                    || key.endsWith("count") || key.endsWith("size") || key.endsWith("total"))) {
                return true;
            }
            if (value instanceof Collection<?> collection && !collection.isEmpty()) {
                return true;
            }
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()
                && hasStructuredToolEvidence(nested, depth + 1)) {
                return true;
            }
        }
        for (String key : List.of("rows", "columns", "results", "resultSets", "result_sets", "records", "items")) {
            Object value = firstMapValue(map, key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return true;
            }
        }
        for (String key : List.of("routingProjection", "structuredContent", "structured_content",
            "data", "result", "payload", "body", "output")) {
            Object nested = firstMapValue(map, key);
            if (hasStructuredToolEvidence(nested, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyMapKey(Map<?, ?> map, String... keys) {
        if (map == null || map.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (firstMapValue(map, key) != null) {
                return true;
            }
        }
        return false;
    }

    private StepReview localToolResultReview(InterpretationPlan.Step step, StepExecution execution) {
        if (execution == null || !execution.success()) {
            return null;
        }
        if (isWebSearchTool(execution.toolName())) {
            int observationCount = structuredObservationCount(execution.output(), 0);
            if (observationCount > 0) {
                return StepReview.accepted(
                    "Unified web_search returned " + observationCount
                        + " governed structured observation row(s); model review is unnecessary.",
                    mapOf(
                        "localFactCheckHasEvidence", true,
                        "localFactCheckEvidenceType", "structured_data_observations",
                        "localFactCheckReason", "tool returned declared structured observation rows, not only discovery metadata",
                        "structuredObservationCount", observationCount,
                        "structuredObservationStepId", step == null ? null : step.id()
                    )
                );
            }
        }
        if (isAssetDiscoveryTool(execution.toolName())) {
            int returnedCount = discoveredAssetCount(execution.output(), "assets");
            if (returnedCount <= 0) {
                return null;
            }
            return StepReview.accepted(
                "Asset discovery returned " + returnedCount + " candidate asset(s); continue to dependent execution step.",
                mapOf(
                    "localFactCheckHasEvidence", true,
                    "localFactCheckEvidenceType", "asset_discovery",
                    "localFactCheckReason", "typed asset discovery returned non-empty asset metadata",
                    "assetDiscoveryReturnedCount", returnedCount,
                    "assetDiscoveryStepId", step == null ? null : step.id()
                )
            );
        }
        if (isTemplateDiscoveryTool(execution.toolName())) {
            String resultCode = discoveryResultCode(execution.output(), 0);
            if ("QUERY_CLAUSE_LIMIT_EXCEEDED".equalsIgnoreCase(resultCode)) {
                return StepReview.rejected(
                    "QUERY_CLAUSE_LIMIT_EXCEEDED: template retrieval exceeded the search clause limit; "
                        + "model review must rewrite a compact, intent-focused keyword set and retry template discovery.",
                    mapOf(
                        "localFactCheckHasEvidence", true,
                        "localFactCheckEvidenceType", "template_discovery_retrieval_limit",
                        "localFactCheckReason", "template search returned a retryable clause-limit diagnostic",
                        "transportSuccess", true,
                        "operationSuccess", false,
                        "businessSatisfied", false,
                        "resultCode", "QUERY_CLAUSE_LIMIT_EXCEEDED",
                        "retryable", true,
                        "nextAction", "REWRITE_TEMPLATE_SEARCH_KEYWORDS_AND_RETRY",
                        "templateDiscoveryReturnedCount", 0,
                        "templateDiscoveryStepId", step == null ? null : step.id()
                    )
                );
            }
            int returnedCount = discoveredAssetCount(execution.output(), "templates");
            if (returnedCount <= 0) {
                return StepReview.rejected(
                    "NO_MATCHING_TEMPLATE: template discovery completed without an executable template; dependent execution must not continue.",
                    mapOf(
                        "localFactCheckHasEvidence", true,
                        "localFactCheckEvidenceType", "template_discovery",
                        "localFactCheckReason", "typed template discovery returned no template metadata",
                        "transportSuccess", true,
                        "operationSuccess", true,
                        "businessSatisfied", false,
                        "resultCode", "NO_MATCHING_TEMPLATE",
                        "templateDiscoveryReturnedCount", 0,
                        "templateDiscoveryStepId", step == null ? null : step.id()
                    )
                );
            }
            return StepReview.accepted(
                "Template discovery returned " + returnedCount + " candidate template(s); continue to dependent execution step.",
                mapOf(
                    "localFactCheckHasEvidence", true,
                    "localFactCheckEvidenceType", "template_discovery",
                    "localFactCheckReason", "typed template discovery returned non-empty template metadata",
                    "templateDiscoveryReturnedCount", returnedCount,
                    "templateDiscoveryStepId", step == null ? null : step.id()
                )
            );
        }
        if (isEnterpriseMetadataSearchTool(execution.toolName())) {
            Map<String, Object> result = enterpriseMetadataResult(execution.output(), 0);
            if (!result.isEmpty()) {
                Map<?, ?> sourceSchema = result.get("sourceSchema") instanceof Map<?, ?> rawSourceSchema
                    ? rawSourceSchema
                    : Map.of();
                Map<String, Object> coverage = result.get("coverage") instanceof Map<?, ?> rawCoverage
                    ? new LinkedHashMap<>((Map<String, Object>) rawCoverage)
                    : Map.of();
                int sourceFieldCount = firstPositiveInteger(
                    result.get("sourceFieldCount"),
                    firstMapValue(sourceSchema, "fieldCount"),
                    collectionSize(firstMapValue(sourceSchema, "fields")));
                int processedFieldCount = firstPositiveInteger(
                    coverage.get("processedFieldCount"),
                    result.get("matchedFieldCount"),
                    collectionSize(result.get("fieldMatches")));
                boolean allFieldsProcessed = Boolean.TRUE.equals(booleanValue(coverage.get("allFieldsProcessed")))
                    || (sourceFieldCount > 0 && processedFieldCount == sourceFieldCount);
                if (sourceFieldCount > 0 && processedFieldCount > 0) {
                    return StepReview.accepted(
                        "Enterprise metadata search processed " + processedFieldCount + " of "
                            + sourceFieldCount + " source field(s); preserve the field evidence for review.",
                        mapOf(
                            "localFactCheckHasEvidence", true,
                            "localFactCheckEvidenceType", "enterprise_metadata_fields",
                            "enterpriseMetadataSourceFieldCount", sourceFieldCount,
                            "enterpriseMetadataProcessedFieldCount", processedFieldCount,
                            "enterpriseMetadataAllFieldsProcessed", allFieldsProcessed,
                            "enterpriseMetadataStepId", step == null ? null : step.id()
                        )
                    );
                }
            }
        }
        if (isSqlMetadataSearchTool(execution.toolName())) {
            int columnCount = sqlColumnMetadataCount(execution.output());
            if (columnCount <= 0) {
                return null;
            }
            return StepReview.accepted(
                "SQL metadata search returned " + columnCount + " cached column metadata item(s); structure evidence is valid and should be preserved for final rendering.",
                mapOf(
                    "localFactCheckHasEvidence", true,
                    "localFactCheckEvidenceType", "sql_metadata_search_columns",
                    "localFactCheckReason", "sql_metadata_search returned non-empty results[].columns metadata",
                    "sqlMetadataFactChecked", true,
                    "sqlMetadataColumnCount", columnCount,
                    "sqlMetadataStepId", step == null ? null : step.id()
                )
            );
        }
        if (isSqlQueryExecuteTool(execution.toolName())) {
            int columnCount = sqlColumnMetadataCount(execution.output());
            if (columnCount <= 0) {
                return null;
            }
            return StepReview.accepted(
                "SQL query returned " + columnCount + " column metadata row(s); structure evidence is valid and should not be rejected only because indexes or data distribution require follow-up queries.",
                mapOf(
                    "localFactCheckHasEvidence", true,
                    "localFactCheckEvidenceType", "sql_column_metadata",
                    "localFactCheckReason", "sql_query_execute returned non-empty information_schema.columns metadata",
                    "sqlMetadataFactChecked", true,
                    "sqlMetadataColumnCount", columnCount,
                    "sqlMetadataStepId", step == null ? null : step.id()
                )
            );
        }
        return null;
    }

    private String discoveryResultCode(Object output, int depth) {
        if (output == null || depth > 6) {
            return null;
        }
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) {
            return discoveryResultCode(normalized, depth + 1);
        }
        if (!(output instanceof Map<?, ?> map)) {
            return null;
        }
        Object direct = firstMapValue(map, "resultCode", "result_code", "code");
        if (direct != null && !String.valueOf(direct).isBlank()) {
            return String.valueOf(direct).trim();
        }
        for (String key : List.of(
            "retrievalReview", "retrieval_review", "structuredContent", "structured_content",
            "routingProjection", "preview", "data", "result", "payload", "body", "output"
        )) {
            String nested = discoveryResultCode(firstMapValue(map, key), depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private boolean reviewContradictsLocalFacts(StepReview review, Map<String, Object> metadata) {
        if (review == null || review.satisfied() || metadata == null || metadata.isEmpty()) {
            return false;
        }
        String reason = review.reason() == null ? "" : review.reason().toLowerCase(Locale.ROOT);
        if (reason.isBlank()) {
            return false;
        }
        Integer assetCount = integerValue(metadata.get("assetDiscoveryReturnedCount"));
        if (assetCount != null && assetCount > 0 && mentionsNoAssetEvidence(reason)) {
            return true;
        }
        Integer templateCount = integerValue(metadata.get("templateDiscoveryReturnedCount"));
        if (templateCount != null && templateCount > 0 && mentionsNoTemplateEvidence(reason)) {
            return true;
        }
        Integer enterpriseFieldCount = integerValue(metadata.get("enterpriseMetadataProcessedFieldCount"));
        if (enterpriseFieldCount != null && enterpriseFieldCount > 0
            && mentionsNoEnterpriseMetadataEvidence(reason)) {
            return true;
        }
        Integer columnCount = integerValue(metadata.get("sqlMetadataColumnCount"));
        return columnCount != null && columnCount > 0 && mentionsNoSqlMetadataEvidence(reason);
    }

    private boolean mentionsNoAssetEvidence(String reason) {
        return containsAny(reason,
            "zero result", "0 result", "returned zero", "returned 0", "no asset", "no matching asset",
            "assets=[]", "returned no asset",
            "\u67e5\u8be2\u52300", "\u8fd4\u56de0", "0\u4e2a\u5339\u914d\u8d44\u4ea7",
            "\u6ca1\u6709\u8d44\u4ea7", "\u65e0\u8d44\u4ea7", "\u672a\u8fd4\u56de\u8d44\u4ea7",
            "\u65e0\u6cd5\u5339\u914d\u53ef\u7528\u7684 sql \u6570\u636e\u6e90"
        );
    }

    private boolean mentionsNoTemplateEvidence(String reason) {
        return containsAny(reason,
            "zero template", "0 template", "no template", "returned no template", "templates=[]",
            "\u6ca1\u6709\u6a21\u677f", "\u65e0\u6a21\u677f", "\u672a\u8fd4\u56de\u6a21\u677f"
        );
    }

    private boolean mentionsNoSqlMetadataEvidence(String reason) {
        return containsAny(reason,
            "no metadata", "no column", "returned no row", "rowcount=0", "row count 0", "rows=[]",
            "\u6ca1\u6709\u5143\u6570\u636e", "\u65e0\u5143\u6570\u636e",
            "\u672a\u8fd4\u56de\u5b57\u6bb5", "\u6ca1\u6709\u5b57\u6bb5",
            "\u6ca1\u6709\u4efb\u4f55\u5173\u4e8e\u8be5\u8868", "\u672a\u8fd4\u56de\u4efb\u4f55\u5173\u4e8e\u8be5\u8868"
        );
    }

    private boolean mentionsNoEnterpriseMetadataEvidence(String reason) {
        return mentionsNoSqlMetadataEvidence(reason) || containsAny(reason,
            "no standard field", "no enterprise metadata", "standard fields were not returned",
            "\u6ca1\u6709\u6807\u51c6\u5b57\u6bb5", "\u672a\u8fd4\u56de\u6807\u51c6\u5b57\u6bb5",
            "\u65e0\u4f01\u4e1a\u5143\u6570\u636e", "\u4f01\u4e1a\u5143\u6570\u636e\u672a\u8fd4\u56de"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enterpriseMetadataResult(Object output, int depth) {
        if (output == null || depth > 8) {
            return Map.of();
        }
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) {
            return enterpriseMetadataResult(normalized, depth + 1);
        }
        if (!(output instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        String schemaVersion = stringValue(map.get("schemaVersion"));
        if ("enterprise_metadata_field_discovery.v1".equals(schemaVersion)) {
            return map;
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            Map<String, Object> nested = enterpriseMetadataResult(map.get(key), depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return Map.of();
    }

    private int firstPositiveInteger(Object... values) {
        if (values == null) {
            return 0;
        }
        for (Object value : values) {
            Integer parsed = integerValue(value);
            if (parsed != null && parsed > 0) {
                return parsed;
            }
        }
        return 0;
    }

    private int collectionSize(Object value) {
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int sqlColumnMetadataCount(Object output) {
        return sqlColumnMetadataCount(output, 0);
    }

    private int sqlColumnMetadataCount(Object output, int depth) {
        if (output == null || depth > 8) {
            return 0;
        }
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) {
            return sqlColumnMetadataCount(normalized, depth + 1);
        }
        if (output instanceof List<?> list) {
            return looksLikeColumnMetadataRows(list) ? list.size() : 0;
        }
        if (!(output instanceof Map<?, ?> map)) {
            return 0;
        }

        Object rows = firstMapValue(map, "rows", "dataRows", "data_rows");
        if (rows instanceof List<?> rowList && looksLikeColumnMetadataRows(rowList)) {
            return rowList.size();
        }
        int metadataSearchColumnCount = metadataSearchColumnCount(map);
        if (metadataSearchColumnCount > 0) {
            return metadataSearchColumnCount;
        }
        Integer rowCount = integerValue(firstMapValue(map, "rowCount", "row_count", "returnedCount", "returned_count"));
        Object columns = firstMapValue(map, "columns");
        if (rowCount != null && rowCount > 0 && looksLikeColumnMetadataColumns(columns)) {
            return rowCount;
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            Object nested = firstMapValue(map, key);
            int nestedCount = sqlColumnMetadataCount(nested, depth + 1);
            if (nestedCount > 0) {
                return nestedCount;
            }
        }
        Object content = firstMapValue(map, "content");
        if (content instanceof List<?> list) {
            for (Object item : list) {
                Object text = item instanceof Map<?, ?> itemMap ? firstMapValue(itemMap, "text", "content", "data") : item;
                int nestedCount = sqlColumnMetadataCount(text, depth + 1);
                if (nestedCount > 0) {
                    return nestedCount;
                }
            }
        }
        return 0;
    }

    private int metadataSearchColumnCount(Map<?, ?> map) {
        Object results = firstMapValue(map, "results", "items", "records");
        if (results instanceof List<?> resultList) {
            for (Object item : resultList) {
                if (!(item instanceof Map<?, ?> itemMap)) {
                    continue;
                }
                int count = metadataSearchColumnCount(itemMap);
                if (count > 0) {
                    return count;
                }
            }
        }
        Object columns = firstMapValue(map, "columns");
        if (columns instanceof List<?> columnList && looksLikeMetadataSearchColumns(columnList)) {
            return columnList.size();
        }
        Integer columnCount = integerValue(firstMapValue(map, "columnCount", "column_count"));
        return columnCount == null ? 0 : Math.max(0, columnCount);
    }

    private boolean looksLikeMetadataSearchColumns(List<?> columns) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        return columns.stream().anyMatch(column -> {
            if (!(column instanceof Map<?, ?> map)) {
                return false;
            }
            return firstMapValue(map, "name", "columnName", "column_name", "COLUMN_NAME") != null
                && firstMapValue(map, "columnType", "dataType", "type", "column_type", "COLUMN_TYPE", "DATA_TYPE", "data_type") != null;
        });
    }

    private boolean looksLikeColumnMetadataRows(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        return rows.stream().anyMatch(row -> {
            if (!(row instanceof Map<?, ?> map)) {
                return false;
            }
            return firstMapValue(map, "COLUMN_NAME", "column_name") != null
                && firstMapValue(map, "COLUMN_TYPE", "column_type", "DATA_TYPE", "data_type") != null;
        });
    }

    private boolean looksLikeColumnMetadataColumns(Object columns) {
        if (!(columns instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        Set<String> normalizedColumns = list.stream()
            .filter(item -> item != null)
            .map(item -> String.valueOf(item).trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return normalizedColumns.contains("column_name")
            && (normalizedColumns.contains("column_type") || normalizedColumns.contains("data_type"));
    }

    private int discoveredAssetCount(Object output, String listKey) {
        return discoveredAssetCount(output, listKey, 0);
    }

    private int discoveredAssetCount(Object output, String listKey, int depth) {
        if (output == null || depth > 6) {
            return 0;
        }
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) {
            return discoveredAssetCount(normalized, listKey, depth + 1);
        }
        if (!(output instanceof Map<?, ?> map)) {
            return output instanceof List<?> list ? list.size() : 0;
        }
        int explicit = discoveryValueCount(firstMapValue(map, listKey), listKey);
        if (explicit > 0) {
            return explicit;
        }
        for (String key : List.of("selectedAsset", "selected_asset", "selected", "asset", "template")) {
            int selected = discoveryValueCount(firstMapValue(map, key), listKey);
            if (selected > 0) {
                return selected;
            }
        }
        if ("templates".equals(listKey)) {
            int nestedTemplates = associatedTemplateCount(map, depth + 1);
            if (nestedTemplates > 0) {
                return nestedTemplates;
            }
        }
        Integer returnedCount = integerValue(firstMapValue(map, "returnedCount", "returned_count", "count"));
        if (returnedCount != null) {
            return Math.max(0, returnedCount);
        }
        Object explicitValue = firstMapValue(map, listKey);
        if (explicitValue != null) {
            int nestedExplicit = discoveredAssetCount(explicitValue, listKey, depth + 1);
            if (nestedExplicit > 0) {
                return nestedExplicit;
            }
        }
        for (String key : List.of("routingProjection", "preview", "structuredContent", "structured_content",
            "data", "result", "payload", "body", "output")) {
            Object nested = firstMapValue(map, key);
            if (nested != null) {
                int nestedCount = discoveredAssetCount(nested, listKey, depth + 1);
                if (nestedCount > 0) {
                    return nestedCount;
                }
            }
        }
        Object content = firstMapValue(map, "content");
        if (content instanceof List<?> list) {
            for (Object item : list) {
                Object text = item instanceof Map<?, ?> itemMap ? firstMapValue(itemMap, "text", "content", "data") : item;
                int nestedCount = discoveredAssetCount(text, listKey, depth + 1);
                if (nestedCount > 0) {
                    return nestedCount;
                }
            }
        }
        return 0;
    }

    private int associatedTemplateCount(Object value, int depth) {
        if (value == null || depth > 6) {
            return 0;
        }
        Object normalized = normalizeToolProtocolPayload(value);
        if (normalized != value) {
            return associatedTemplateCount(normalized, depth + 1);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .mapToInt(item -> associatedTemplateCount(item, depth + 1))
                .sum();
        }
        if (!(value instanceof Map<?, ?> map)) {
            return 0;
        }
        for (String key : List.of("associatedTemplates", "associated_templates", "sqlTemplates", "sql_templates")) {
            int count = discoveryValueCount(firstMapValue(map, key), "templates");
            if (count > 0) {
                return count;
            }
        }
        for (String key : List.of("results", "items", "hits", "candidates", "data", "result", "payload")) {
            int count = associatedTemplateCount(firstMapValue(map, key), depth + 1);
            if (count > 0) {
                return count;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeToolProtocolPayload(Object output) {
        if (output instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return output;
            }
            try {
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    return RESULT_OBJECT_MAPPER.readValue(trimmed, new TypeReference<Map<String, Object>>() {
                    });
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    return RESULT_OBJECT_MAPPER.readValue(trimmed, new TypeReference<List<Object>>() {
                    });
                }
            } catch (RuntimeException ignored) {
                return output;
            } catch (Exception ignored) {
                return output;
            }
        }
        if (output instanceof Map<?, ?> map && !(output instanceof LinkedHashMap<?, ?>)) {
            return new LinkedHashMap<>((Map<Object, Object>) map);
        }
        return output;
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private int discoveryValueCount(Object value, String listKey) {
        if (value == null) {
            return 0;
        }
        if (value instanceof List<?> list) {
            long count = list.stream()
                .filter(item -> looksLikeDiscoveryItem(item, listKey))
                .count();
            return Math.toIntExact(Math.min(Integer.MAX_VALUE, count));
        }
        if (looksLikeDiscoveryItem(value, listKey)) {
            return 1;
        }
        return 0;
    }

    private boolean looksLikeDiscoveryItem(Object value, String listKey) {
        if (value == null) {
            return false;
        }
        if ("assets".equals(listKey)) {
            return looksLikeAssetDiscoveryItem(value);
        }
        if ("templates".equals(listKey)) {
            return looksLikeTemplateDiscoveryItem(value);
        }
        return value instanceof Map<?, ?> map && !map.isEmpty();
    }

    private boolean looksLikeAssetDiscoveryItem(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        Object nestedAsset = firstMapValue(map, "asset", "datasource", "target");
        if (nestedAsset instanceof Map<?, ?> nestedMap && looksLikeAssetDiscoveryItem(nestedMap)) {
            return true;
        }
        return firstMapValue(map, "name", "assetName", "asset_name", "displayName", "toolName", "tool_name", "id") != null
            && firstMapValue(map, "environment", "env", "databaseType", "database_type", "toolName", "tool_name", "id") != null;
    }

    private boolean looksLikeTemplateDiscoveryItem(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        return firstMapValue(map, "templateId", "template_id", "id", "code", "name") != null
            && firstMapValue(map, "parameterSchema", "parameter_schema", "scope", "targetLevel", "target_level",
                "databaseType", "database_type", "templateId", "template_id", "id") != null;
    }

    private int toolResultReviewMaxAttempts(ExecutionRequest request) {
        Object configured = request == null || request.attributes() == null
            ? null
            : request.attributes().get("toolResultReviewMaxAttempts");
        if (configured instanceof Number number) {
            return Math.max(1, Math.min(3, number.intValue()));
        }
        if (configured != null) {
            try {
                return Math.max(1, Math.min(3, Integer.parseInt(String.valueOf(configured))));
            } catch (NumberFormatException ignored) {
                return 3;
            }
        }
        return 1;
    }

    private Map<String, Object> executionLock(InterpretationPlan.Step step, StepReview review) {
        if (review == null || review.metadata() == null || !review.metadata().containsKey("evidenceEvaluation")) {
            return Map.of();
        }
        EvidenceExecutionLock lock = EvidenceExecutionLock.fromReview(
            step == null ? null : step.id(),
            step == null ? "" : step.toolName(),
            review.reason(),
            review.metadata()
        );
        EvidenceLockGraph lockGraph = EvidenceLockGraph.fromReview(
            step == null ? null : step.id(),
            step == null ? "" : step.toolName(),
            review.metadata(),
            lock
        );
        Map<String, Object> value = new LinkedHashMap<>(lock.toMetadata());
        value.put("lockGraph", lockGraph.toMetadata());
        value.put("reviewSatisfied", review.satisfied());
        return value;
    }

    private List<StepExecution> hydrateCompletedExecutionsFromEvents(String runId,
                                                                     Set<Integer> completedStepIds,
                                                                     Map<Integer, StepExecution> completed,
                                                                     Map<Integer, InterpretationPlan.Step> plannedSteps,
                                                                     ExecutionRequest request) {
        if (runStore == null || runId == null || runId.isBlank()
            || completedStepIds == null || completedStepIds.isEmpty() || completed == null
            || plannedSteps == null || plannedSteps.isEmpty()) {
            return List.of();
        }
        Map<Integer, AgentObservation> rawObservations = rawObservationsByStep(runId, request);
        Map<Integer, PlanStepCheckpoint> checkpoints = persistedCheckpointMap(runId);
        List<StepExecution> hydrated = new ArrayList<>();
        for (AgentRunEvent event : runStore.events(runId)) {
            if (event == null || event.type() != AgentRunEventType.OBSERVATION_RECORDED) {
                continue;
            }
            Map<String, Object> payload = asStringMap(event.payload());
            Map<String, Object> eventMetadata = asStringMap(payload.get("metadata"));
            if (!samePlanExecutionScope(eventMetadata, request)) {
                continue;
            }
            Integer stepId = integerValue(firstPresent(
                eventMetadata, "interpretationPlanStepId", "workflowStepId", "stepId"));
            if (stepId == null || !completedStepIds.contains(stepId) || completed.containsKey(stepId)) {
                continue;
            }
            AgentObservation rawObservation = rawObservations.get(stepId);
            Map<String, Object> metadata = rawObservation == null
                ? eventMetadata
                : new LinkedHashMap<>(rawObservation.metadata());
            if (!Boolean.TRUE.equals(booleanValue(firstPresent(metadata, "success", "toolSuccess")))) {
                continue;
            }
            String actionType = stringValue(firstPresent(
                metadata, "interpretationPlanActionType", "actionType"));
            String toolName = stringValue(firstPresent(metadata, "toolName", "source"));
            String storedDefinitionFingerprint = stringValue(firstPresent(
                metadata, "planStepDefinitionFingerprint", "definitionFingerprint"));
            InterpretationPlan.Step plannedStep = plannedSteps.get(stepId);
            if (!matchesStoredStepIdentity(
                plannedStep, actionType, toolName, storedDefinitionFingerprint)) {
                log.info("Ignored stale completed plan step after rewrite stepId={} storedAction={} "
                        + "storedTool={} plannedAction={} plannedTool={}",
                    stepId,
                    actionType,
                    toolName,
                    plannedStep == null ? null : plannedStep.actionType(),
                    plannedStep == null ? null : plannedStep.toolName());
                continue;
            }
            PlanStepCheckpoint checkpoint = checkpoints.get(stepId);
            if (checkpoint != null && !validCheckpoint(checkpoint, plannedStep, completed, request)) {
                log.info("Ignored stale completed plan step because its checkpoint dependency chain changed. "
                        + "runId={} stepId={}", runId, stepId);
                continue;
            }
            Object output = outputFromObservationMetadata(metadata);
            String finalAnswer = finalAnswerFromHydratedObservation(actionType, output, metadata);
            StepExecution execution = new StepExecution(
                stepId,
                actionType,
                toolName,
                true,
                output,
                null,
                null,
                finalAnswer,
                longValue(metadata.get("durationMs"), 0L),
                Map.of(
                    "hydratedFromRunStoreObservation", true,
                    "hydratedFromRawObservation", rawObservation != null
                )
            );
            completed.put(stepId, execution);
            hydrated.add(execution);
        }
        return List.copyOf(hydrated);
    }

    private Map<Integer, PlanStepCheckpoint> persistedCheckpointMap(String runId) {
        if (runStore == null || runId == null || runId.isBlank()) {
            return Map.of();
        }
        try {
            return runStore.planStepCheckpoints(runId).stream()
                .filter(Objects::nonNull)
                .filter(checkpoint -> checkpoint.stepId() != null)
                .collect(Collectors.toMap(
                    PlanStepCheckpoint::stepId,
                    checkpoint -> checkpoint,
                    (left, right) -> left.updatedAt() >= right.updatedAt() ? left : right,
                    LinkedHashMap::new
                ));
        } catch (RuntimeException ex) {
            log.warn("Failed to read plan checkpoints while hydrating observations. runId={} error={}",
                runId, ex.getMessage());
            return Map.of();
        }
    }

    private boolean matchesStoredStepIdentity(InterpretationPlan.Step plannedStep,
                                              String storedActionType,
                                              String storedToolName,
                                              String storedDefinitionFingerprint) {
        if (plannedStep == null || storedActionType == null
            || !storedActionType.equals(plannedStep.actionType())) {
            return false;
        }
        if (storedDefinitionFingerprint != null && !storedDefinitionFingerprint.isBlank()
            && !Objects.equals(storedDefinitionFingerprint, stepFingerprint(plannedStep))) {
            return false;
        }
        // A final answer belongs to the current plan revision. Reusing a terminal event from
        // an earlier revision can make Runtime report success without executing the current
        // final step, which is then correctly rejected by the workflow completion guard.
        if (plannedStep.finalAnswerAction()) {
            return false;
        }
        if (!"mcp_tool".equals(plannedStep.actionType())) {
            return true;
        }
        return !toolSemanticKey(storedToolName).isBlank()
            && toolSemanticKey(storedToolName).equals(toolSemanticKey(plannedStep.toolName()));
    }

    private Object outputFromObservationMetadata(Map<String, Object> metadata) {
        Object documentId = firstPresent(metadata, "stepOutputDocumentId", "step_output_document_id");
        if (documentId != null && runStore != null) {
            Object external = runStore.evidence(String.valueOf(documentId)).orElse(null);
            if (external != null) {
                return parseStoredOutput(external);
            }
        }
        Object stored = firstPresent(metadata, "stepOutput", "output", "data", "stepOutputPreview");
        return parseStoredOutput(stored);
    }

    private Object parseStoredOutput(Object stored) {
        if (stored == null) {
            return null;
        }
        if (!(stored instanceof String text)) {
            return stored;
        }
        String trimmed = text.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        try {
            return RESULT_OBJECT_MAPPER.readValue(trimmed, Object.class);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private String finalAnswerFromHydratedObservation(String actionType,
                                                      Object output,
                                                      Map<String, Object> metadata) {
        if (!"final_answer".equals(actionType)) {
            return null;
        }
        Object answer = output instanceof Map<?, ?> map
            ? firstPresent(asStringMap(map), "answer", "response", "text", "result")
            : null;
        if (answer == null) {
            answer = firstPresent(metadata, "finalAnswer", "final_answer", "answer");
        }
        return answer == null || String.valueOf(answer).isBlank() ? null : String.valueOf(answer);
    }

    private Map<Integer, AgentObservation> rawObservationsByStep(String runId, ExecutionRequest request) {
        if (runStore == null || runId == null || runId.isBlank()) {
            return Map.of();
        }
        Map<Integer, AgentObservation> observations = new LinkedHashMap<>();
        for (AgentObservation observation : runStore.observations(runId)) {
            if (observation == null || observation.metadata() == null) {
                continue;
            }
            if (!samePlanExecutionScope(observation.metadata(), request)) {
                continue;
            }
            Integer stepId = integerValue(firstPresent(
                observation.metadata(), "interpretationPlanStepId", "workflowStepId", "stepId"));
            if (stepId != null) {
                observations.put(stepId, observation);
            }
        }
        return observations;
    }

    private boolean sameWorkflowExecutionAttempt(Object storedAttempt, Object currentAttempt) {
        return normalizedWorkflowExecutionAttempt(storedAttempt)
            .equals(normalizedWorkflowExecutionAttempt(currentAttempt));
    }

    private String normalizedWorkflowExecutionAttempt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "0";
        }
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        return String.valueOf(value).trim();
    }

    private Map<String, Object> resolvedStepInput(InterpretationPlan.Step step,
                                                  ExecutionRequest request,
                                                  Map<Integer, StepExecution> completed) {
        Map<String, Object> input = new LinkedHashMap<>(step.input() == null ? Map.of() : step.input());
        InterpretationPlan plan = request == null ? null : request.plan();
        applyBindings(step, plan, completed, input, request);
        if (batchToolInput(input) && !runtimeOwnedTemplateBatch(step, plan, completed)) {
            bridgeBatchTemplateInvocations(step, request, completed, input);
            return input;
        }
        establishRuntimeTemplateBinding(step, completed, input);
        normalizeModelInvocationEnvelope(step, input);
        normalizeWebSearchInput(step, request, input);
        normalizeNewsSearchInput(step, request, input);
        applyPublishedInputAdapterContract(step, request, completed, input);
        Map<String, Object> retrievalGate = applyStepInputEnricher(step, request, completed, input);
        // Discovery schemas require the runtime-owned filters envelope. Normalize loose model
        // arguments before compiling against the published schema; otherwise a valid semantic
        // request such as {intent: ..., templateIds: [...]} fails on the missing filters field and
        // the fallback agent loop loses the discovered-template scope.
        normalizeDiscoveryRoutingInput(step, request, completed, input);
        compileDirectToolArguments(step, request, completed, input);
        hydrateExecutionContextFromCompletedAssets(step, completed, input);
        normalizeSqlExecutionContext(step, input);
        boolean runtimeOwnsTemplateBatch = runtimeOwnedTemplateBatch(step, plan, completed);
        Map<Integer, StepExecution> contractContext = runtimeOwnsTemplateBatch
            ? completed
            : resolveTemplateContractFromMcp(step, request, completed, input);
        if (!runtimeOwnsTemplateBatch) {
            bridgeTemplateInvocation(step, request, contractContext, input);
            validateTemplateExecutionArgumentContract(step, input);
        }
        hydrateExecutionContextFromTemplateMetadata(step, contractContext, input);
        hydrateSqlMetadataParametersFromMetadataSearch(step, contractContext, input);
        repairTableScopedSqlTemplate(step, contractContext, input);
        enforceAgentRuntimeEnvironment(step, request, input);
        if (!runtimeOwnsTemplateBatch) {
            validateRequiredExecutionTemplate(step, input, completed);
        }
        enforceCanonicalAssetContinuity(step, completed, input);
        input.remove("runtimeParameterProtocolApplied");
        if (!retrievalGate.isEmpty()) {
            input.put(MODEL_RETRIEVAL_GATE_KEY, retrievalGate);
        }
        if (!isCrawlerTool(step.toolName())) {
            return input;
        }
        List<String> selectedUrls = selectedUrlsFromCompletedWebSearch(completed);
        if (selectedUrls.isEmpty() || hasNonBlank(input, "url", "href", "sourceUrl", "source_url")) {
            return input;
        }
        input.put("url", selectedUrls.get(0));
        return input;
    }

    private Map<String, Object> applyStepInputEnricher(InterpretationPlan.Step step,
                                                       ExecutionRequest request,
                                                       Map<Integer, StepExecution> completed,
                                                       Map<String, Object> input) {
        if (stepInputEnricher == null || step == null || input == null) {
            return Map.of();
        }
        Map<String, Object> enriched = stepInputEnricher.enrich(new StepInputEnrichmentRequest(
            step,
            Map.copyOf(input),
            completed == null ? Map.of() : Map.copyOf(completed),
            request
        ));
        if (enriched == null) {
            return Map.of();
        }
        Map<String, Object> gate = new LinkedHashMap<>(
            asStringMap(enriched.remove(MODEL_RETRIEVAL_GATE_KEY))
        );
        input.clear();
        input.putAll(enriched);
        return gate;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> restoreOriginalRetrievalArguments(Map<String, Object> enhanced,
                                                                  Map<String, Object> gate) {
        Map<String, Object> restored = deepMutableMap(enhanced);
        Map<String, Object> originalValues = asStringMap(gate.get("originalValues"));
        for (Map.Entry<String, Object> entry : originalValues.entrySet()) {
            putNestedValue(restored, entry.getKey(), entry.getValue());
        }
        for (String path : stringValues(gate.get("originallyAbsentPaths"))) {
            removeNestedValue(restored, path);
        }
        return restored;
    }

    private boolean equivalentTemplateRetrievalRequest(String toolName,
                                                       Map<String, Object> enhanced,
                                                       Map<String, Object> original) {
        String normalizedTool = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        if (!(normalizedTool.contains("template_query") || normalizedTool.contains("template_search"))
            || enhanced == null || original == null
            || !(enhanced.get("filters") instanceof Map<?, ?>)
            || !(original.get("filters") instanceof Map<?, ?>)) {
            return false;
        }
        for (String key : List.of("filters", "limit", "targetKind", "assetType", "finalDecision")) {
            if (!Objects.equals(enhanced.get(key), original.get(key))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                value = deepMutableMap((Map<String, Object>) map);
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void putNestedValue(Map<String, Object> root, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Object nested = current.get(segments[index]);
            Map<String, Object> next = nested instanceof Map<?, ?>
                ? deepMutableMap((Map<String, Object>) nested)
                : new LinkedHashMap<>();
            current.put(segments[index], next);
            current = next;
        }
        current.put(segments[segments.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private void removeNestedValue(Map<String, Object> root, String path) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Object nested = current.get(segments[index]);
            if (!(nested instanceof Map<?, ?> map)) {
                return;
            }
            current = (Map<String, Object>) map;
        }
        current.remove(segments[segments.length - 1]);
    }

    private boolean batchToolInput(Map<String, Object> input) {
        if (input == null) {
            return false;
        }
        Object rawCalls = firstPresent(input, "calls", "toolCalls", "tool_calls");
        return rawCalls instanceof List<?> calls && !calls.isEmpty();
    }

    private boolean runtimeOwnedDiagnosticBatch(InterpretationPlan.Step step,
                                                InterpretationPlan plan) {
        if (step == null || step.id() == null || plan == null || plan.plan() == null
            || plan.plan().diagnosticProfile() == null
            || plan.plan().diagnosticProfile().checks() == null) {
            return false;
        }
        long requiredChecks = plan.plan().diagnosticProfile().checks().stream()
            .filter(Objects::nonNull)
            .filter(check -> !Boolean.FALSE.equals(check.required()))
            .filter(check -> check.stepIds() != null && check.stepIds().contains(step.id()))
            .count();
        return requiredChecks >= 2;
    }

    private boolean runtimeOwnedTemplateBatch(InterpretationPlan.Step step,
                                              InterpretationPlan plan,
                                              Map<Integer, StepExecution> completed) {
        return runtimeOwnedDiagnosticBatch(step, plan)
            || runtimeOwnedReviewedTemplateBatch(step, completed);
    }

    private boolean runtimeOwnedReviewedTemplateBatch(InterpretationPlan.Step step,
                                                       Map<Integer, StepExecution> completed) {
        if (step == null || !isTemplateExecutionTool(step.toolName())) {
            return false;
        }
        return reviewedSelectedTemplateIds(completed).size() >= 2;
    }

    private boolean declaresBatchTransport(Map<String, Object> input) {
        return input != null && (input.containsKey("calls")
            || input.containsKey("toolCalls") || input.containsKey("tool_calls"));
    }

    /**
     * Applies the same Runtime-owned discovery and parameter-evidence contract to every child of
     * a model-produced batch. A batch is only a transport optimization; it must not bypass the
     * scalar template invocation bridge.
     */
    @SuppressWarnings("unchecked")
    private void bridgeBatchTemplateInvocations(InterpretationPlan.Step step,
                                                ExecutionRequest request,
                                                Map<Integer, StepExecution> completed,
                                                Map<String, Object> input) {
        boolean governedDiscoveryAvailable = completed != null && completed.values().stream()
            .anyMatch(execution -> execution != null && execution.success()
                && isTemplateDiscoveryTool(execution.toolName()));
        if (!governedDiscoveryAvailable) {
            return;
        }
        Object rawCalls = firstPresent(input, "calls", "toolCalls", "tool_calls");
        if (!(rawCalls instanceof List<?> calls)) {
            return;
        }
        List<Map<String, Object>> bridgedCalls = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            if (!(calls.get(index) instanceof Map<?, ?> rawCall)) {
                throw new IllegalStateException("TEMPLATE_BATCH_CONTRACT_FAILED: call " + index
                    + " must be an object");
            }
            Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) rawCall);
            String preflightErrorCode = stringValue(firstMapValue(
                call, "preflightErrorCode", "preflight_error_code"));
            if (preflightErrorCode != null && !preflightErrorCode.isBlank()) {
                bridgedCalls.add(call);
                log.info("InterpretationPlan retained terminal preflight result for Runtime-owned "
                        + "batch child: stepId={}, callIndex={}, callId={}, errorCode={}",
                    step == null ? null : step.id(), index,
                    firstMapValue(call, "callId", "call_id"), preflightErrorCode);
                continue;
            }
            Object rawArguments = firstMapValue(call, "arguments", "input");
            if (!(rawArguments instanceof Map<?, ?> argumentMap)) {
                throw new IllegalStateException("TEMPLATE_BATCH_CONTRACT_FAILED: call " + index
                    + " arguments must be an object");
            }
            Map<String, Object> arguments = new LinkedHashMap<>((Map<String, Object>) argumentMap);
            String childTool = firstText(
                stringValue(firstMapValue(call, "toolName", "tool_name")),
                step == null ? null : step.toolName()
            );
            String templateId = canonicalTemplateId(firstValueAtAnyPath(arguments,
                "$.templateId", "$.template", "$.template_id"));
            if (templateId == null) {
                throw new IllegalStateException("TEMPLATE_BATCH_CONTRACT_FAILED: call " + index
                    + " has no Runtime-bound template id");
            }
            Map<String, Object> templateMetadata = completedTemplateMetadata(completed, templateId);
            if (templateMetadata.isEmpty()) {
                throw new IllegalStateException("TEMPLATE_BATCH_CONTRACT_FAILED: call " + index
                    + " template " + templateId + " was not returned by completed discovery");
            }
            Object rawProtocol = firstMapValue(arguments, "parameterProtocol", "parameter_protocol");
            Map<String, Object> protocol = rawProtocol instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : null;
            TemplateInvocationBridge.BridgeResult bridged;
            try {
                bridged = TEMPLATE_INVOCATION_BRIDGE.prepare(
                    new TemplateInvocationBridge.BridgeRequest(
                        childTool,
                        step == null ? null : step.id(),
                        templateId,
                        templateMetadata,
                        arguments,
                        protocol,
                        requiresTemplateParameterProtocol(request),
                        true,
                        templateParameterEvidenceContext(request, completed)
                    )
                );
            } catch (TemplateInvocationBridge.TemplateBridgeException ex) {
                if (!runtimeOwnedTemplateBatch(step, request == null ? null : request.plan(), completed)) {
                    throw ex;
                }
                log.warn("InterpretationPlan skipped Runtime-owned batch child after parameter audit: "
                        + "stepId={}, callIndex={}, tool={}, templateId={}, error={}",
                    step == null ? null : step.id(), index, childTool, templateId, ex.getMessage());
                continue;
            }
            Map<String, Object> childInput = new LinkedHashMap<>(bridged.executorInput());
            childInput.remove(TemplateInvocationBridge.APPLIED_MARKER);
            call.put("arguments", childInput);
            call.remove("input");
            bridgedCalls.add(call);
            log.info("InterpretationPlan template bridge validated batch child stepId={} callIndex={} "
                    + "tool={} templateId={} parameterKeys={} modelProtocolApplied={} protocolTrace={}",
                step == null ? null : step.id(), index, childTool, templateId,
                bridged.parameters().keySet(), bridged.modelProtocolApplied(), bridged.protocolTrace());
        }
        if (bridgedCalls.isEmpty()) {
            throw new IllegalStateException(
                "TEMPLATE_BATCH_PARAMETER_AUDIT_FAILED: no authorized call retained "
                    + "after Runtime parameter evidence validation");
        }
        input.put("calls", bridgedCalls);
        input.remove("toolCalls");
        input.remove("tool_calls");
    }

    /**
     * Applies a tool-published, declarative dependency-evidence adapter contract.
     * Runtime only transports successful declared dependency outputs; it does not
     * inspect tool names, result schemas, fields, or business semantics.
     */
    private void applyPublishedInputAdapterContract(InterpretationPlan.Step step,
                                                    ExecutionRequest request,
                                                    Map<Integer, StepExecution> completed,
                                                    Map<String, Object> input) {
        if (step == null || request == null || request.toolRegistry() == null
            || completed == null || completed.isEmpty() || input == null) {
            return;
        }
        ToolMetadata metadata = request.toolRegistry().getToolMetadata(step.toolName());
        if (metadata == null || metadata.getMetadata() == null) {
            return;
        }
        Map<String, Object> mcpMeta = asStringMap(metadata.getMetadata().get("mcpToolMeta"));
        Map<String, Object> contract = asStringMap(mcpMeta.get("inputAdapterContract"));
        if (!AgentProtocolCatalog.RUNTIME_DEPENDENCY_EVIDENCE.equals(
            stringValue(contract.get("contractVersion")))) {
            return;
        }
        String parameter = stringValue(contract.get("dependencyEvidenceParameter"));
        if (parameter == null || parameter.isBlank() || input.containsKey(parameter)) {
            return;
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Integer dependencyId : safeIntegerList(step.dependsOn())) {
            StepExecution execution = completed.get(dependencyId);
            if (execution == null || !execution.success() || execution.output() == null) {
                continue;
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("stepId", execution.stepId());
            envelope.put("actionType", execution.actionType());
            if (execution.toolName() != null && !execution.toolName().isBlank()) {
                envelope.put("toolName", execution.toolName());
            }
            envelope.put("output", execution.output());
            evidence.add(Map.copyOf(envelope));
        }
        if (evidence.isEmpty()) {
            return;
        }
        input.put(parameter, List.copyOf(evidence));
        log.info("InterpretationPlan applied published input adapter contract stepId={} tool={} contractVersion={} dependencyEvidenceCount={}",
            step.id(), step.toolName(), contract.get("contractVersion"), evidence.size());
    }

    private void normalizeWebSearchInput(InterpretationPlan.Step step,
                                         ExecutionRequest request,
                                         Map<String, Object> input) {
        if (step == null || input == null || !isWebSearchTool(step.toolName())) {
            return;
        }
        if (!hasNonBlank(input, "query")) {
            String query = originalUserQuery(request);
            if (query == null || query.isBlank()) {
                query = stringValues(input.get("queries")).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining(" "));
            }
            if (query == null || query.isBlank()) {
                query = planGoalSearchText(request == null ? null : request.plan());
            }
            if (query != null && !query.isBlank()) {
                input.put("query", query.trim());
            }
        }
        if (!input.containsKey("num_results")) {
            Object resultLimit = firstPresent(input, "max_results", "maxResults", "limit");
            input.put("num_results", resultLimit == null ? 10 : resultLimit);
        }
        input.remove("queries");
        input.remove("max_results");
        input.remove("maxResults");
    }

    private void normalizeNewsSearchInput(InterpretationPlan.Step step,
                                          ExecutionRequest request,
                                          Map<String, Object> input) {
        if (step == null || input == null || !isNewsSearchTool(step.toolName())) {
            return;
        }
        String originalQuery = originalUserQuery(request);
        if (originalQuery == null || originalQuery.isBlank()) {
            return;
        }
        // The user's wording is authoritative. This removes stale dates invented by the planner.
        input.put("query", originalQuery);
        if (!RELATIVE_TODAY_PATTERN.matcher(originalQuery).find()
            || EXPLICIT_CALENDAR_DATE_PATTERN.matcher(originalQuery).find()) {
            return;
        }
        ZoneId zone = runtimeZoneId(request);
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        input.put("startTime", today.atStartOfDay(zone).toInstant().toString());
        input.put("endTime", now.toInstant().toString());
        input.remove("time_range");
        input.remove("timeRange");
        input.remove("category");
        input.remove("max_results");
        input.remove("maxResults");
        log.info("InterpretationPlan resolved relative news date from Runtime stepId={} tool={} date={} timezone={}",
            step.id(), step.toolName(), today, zone.getId());
    }

    private ZoneId runtimeZoneId(ExecutionRequest request) {
        Map<String, Object> attributes = request == null ? null : request.attributes();
        Object configured = attributes == null ? null : firstNonBlankObject(
            attributes.get("timezone"), attributes.get("timeZone"), attributes.get("zoneId"));
        if (configured != null) {
            try {
                return ZoneId.of(String.valueOf(configured).trim());
            } catch (Exception ignored) {
                // Request/model values cannot replace the server Runtime timezone when invalid.
            }
        }
        return ZoneId.systemDefault();
    }

    @SuppressWarnings("unchecked")
    private void normalizeModelInvocationEnvelope(InterpretationPlan.Step step, Map<String, Object> input) {
        if (step == null || input == null) {
            return;
        }
        Object parameterProtocol = firstMapValue(input, "parameterProtocol", "parameter_protocol");
        if (parameterProtocol != null
            && (!isTemplateExecutionTool(step.toolName()) || !(parameterProtocol instanceof Map<?, ?>))) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: parameter protocol must be an "
                + "object attached to a template execution step");
        }
        Object toolCallValue = firstMapValue(input, "toolCall", "tool_call");
        if (toolCallValue instanceof Map<?, ?> rawToolCall) {
            Map<String, Object> toolCall = new LinkedHashMap<>((Map<String, Object>) rawToolCall);
            String selectedTool = stringValue(firstMapValue(toolCall, "toolName", "tool_name"));
            if (selectedTool != null && !sameToolName(selectedTool, step.toolName())) {
                throw new IllegalStateException("TOOL_CALL_CONTRACT_FAILED: toolCall.toolName " + selectedTool
                    + " does not match the planned workflow tool " + step.toolName());
            }
            Object action = firstMapValue(toolCall, "action", "capability", "templateRef", "templateId");
            if (action != null) {
                if (isTemplateExecutionTool(step.toolName())) {
                    input.put("templateId", action);
                } else {
                    input.put("action", action);
                }
            }
            Object parameters = firstMapValue(toolCall, "parameters", "arguments");
            if (parameters instanceof Map<?, ?> map && !isTemplateExecutionTool(step.toolName())) {
                map.forEach((key, item) -> input.put(String.valueOf(key), item));
            } else if (parameters != null) {
                input.put("parameters", parameters);
            }
            Object contextValue = toolCall.get("context");
            if (contextValue instanceof Map<?, ?> context) {
                Object target = firstMapValue(context, "target", "executionContext", "execution_context");
                if (target != null) {
                    input.put("executionContext", target);
                }
                Object purpose = firstMapValue(context, "purpose", "reason");
                if (purpose != null && !String.valueOf(purpose).isBlank()) {
                    input.put("purpose", String.valueOf(purpose).trim());
                }
            }
            input.remove("toolCall");
            input.remove("tool_call");
        }
        if (!isTemplateExecutionTool(step.toolName())) {
            return;
        }
        Object value = firstMapValue(input, "invocation", "modelInvocation", "model_invocation");
        if (!(value instanceof Map<?, ?> raw)) {
            enforceRuntimeTemplateBinding(step, input);
            return;
        }
        Map<String, Object> invocation = new LinkedHashMap<>((Map<String, Object>) raw);
        Object templateRef = firstMapValue(invocation, "templateRef", "template_ref", "templateId", "template");
        if (templateRef != null) {
            input.put("templateId", templateRef);
        }
        Object arguments = firstMapValue(invocation, "arguments", "parameters", "params");
        if (arguments instanceof Map<?, ?> map) {
            input.put("parameters", new LinkedHashMap<>((Map<String, Object>) map));
        } else if (arguments != null) {
            input.put("parameters", arguments);
        }
        Object target = firstMapValue(invocation, "target", "executionContext", "execution_context");
        if (target != null) {
            input.put("executionContext", target);
        }
        Object intent = firstMapValue(invocation, "intent", "purpose", "goal");
        if (intent != null && !String.valueOf(intent).isBlank()) {
            input.putIfAbsent("purpose", String.valueOf(intent).trim());
        }
        input.remove("invocation");
        input.remove("modelInvocation");
        input.remove("model_invocation");
        enforceRuntimeTemplateBinding(step, input);
    }

    @SuppressWarnings("unchecked")
    private void bridgeTemplateInvocation(InterpretationPlan.Step step,
                                          ExecutionRequest request,
                                          Map<Integer, StepExecution> completed,
                                          Map<String, Object> input) {
        if (step == null || input == null || !isTemplateExecutionTool(step.toolName())) {
            return;
        }
        String templateId = runtimeOwnedTemplateId(input);
        boolean runtimeTemplateAuthoritative = templateId != null;
        if (templateId == null) {
            templateId = canonicalTemplateId(firstValueAtAnyPath(input,
                "$.templateId", "$.template", "$.template_id"));
        }
        if (templateId == null) {
            templateId = uniqueCompletedTemplateForExecutor(step.toolName(), completed);
        }
        Map<String, Object> templateMetadata = templateId == null
            ? Map.of() : completedTemplateMetadata(completed, templateId);
        Object rawProtocol = firstMapValue(input, "parameterProtocol", "parameter_protocol");
        Map<String, Object> protocol = rawProtocol instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : null;
        TemplateInvocationBridge.BridgeResult bridged = TEMPLATE_INVOCATION_BRIDGE.prepare(
            new TemplateInvocationBridge.BridgeRequest(
                step.toolName(),
                step.id(),
                templateId,
                templateMetadata,
                input,
                protocol,
                requiresTemplateParameterProtocol(request),
                runtimeTemplateAuthoritative,
                templateParameterEvidenceContext(request, completed)
            )
        );
        input.clear();
        input.putAll(bridged.executorInput());
        boolean diagnosticBatchPending = diagnosticBatchPending(step, request == null ? null : request.plan());
        log.info("InterpretationPlan template bridge validated scalar input stepId={} tool={} templateId={} "
                + "diagnosticBatchPending={} parameterKeys={} modelProtocolApplied={} "
                + "parameterEvidence={} protocolTrace={} repairs={}",
            step.id(),
            step.toolName(),
            bridged.templateId(),
            diagnosticBatchPending,
            bridged.parameters().keySet(),
            bridged.modelProtocolApplied(),
            bridged.parameterEvidence(),
            bridged.protocolTrace(),
            bridged.repairs());
    }

    private boolean diagnosticBatchPending(InterpretationPlan.Step step, InterpretationPlan plan) {
        if (step == null || step.id() == null || plan == null || plan.plan() == null
            || plan.plan().diagnosticProfile() == null
            || plan.plan().diagnosticProfile().checks() == null) {
            return false;
        }
        return plan.plan().diagnosticProfile().checks().stream()
            .filter(Objects::nonNull)
            .filter(check -> !Boolean.FALSE.equals(check.required()))
            .filter(check -> check.stepIds() != null && check.stepIds().contains(step.id()))
            .limit(2)
            .count() >= 2;
    }

    private TemplateInvocationBridge.EvidenceContext templateParameterEvidenceContext(
        ExecutionRequest request,
        Map<Integer, StepExecution> completed
    ) {
        Map<Integer, Object> completedOutputs = new LinkedHashMap<>();
        if (completed != null) {
            completed.forEach((stepId, execution) -> {
                if (stepId != null && execution != null && execution.success() && execution.output() != null) {
                    completedOutputs.put(stepId, execution.output());
                }
            });
        }
        return new TemplateInvocationBridge.EvidenceContext(
            originalUserQuery(request),
            completedOutputs
        );
    }

    private void compileDirectToolArguments(InterpretationPlan.Step step,
                                            ExecutionRequest request,
                                            Map<Integer, StepExecution> completed,
                                            Map<String, Object> input) {
        if (step == null || request == null || input == null || isTemplateExecutionTool(step.toolName())
            || request.toolRegistry() == null) {
            return;
        }
        ToolMetadata metadata = request.toolRegistry().getToolMetadata(step.toolName());
        if (metadata == null) {
            return;
        }
        Map<String, Object> schema = publishedInputSchema(metadata);
        if (schema.isEmpty()) {
            schema = inputSchemaFromParameters(metadata.getParameters());
        }
        if (schema.isEmpty()) {
            return;
        }
        // Discovery trace and schema version are runtime-owned protocol fields. Seed them before
        // validating the published MCP schema so required protocol fields are not incorrectly
        // reported as missing merely because the model is intentionally not responsible for them.
        if (isRoutingDiscoveryTool(step.toolName())) {
            input.putIfAbsent("filtersSchemaVersion", AgentProtocolCatalog.TARGET_FILTERS);
            input.putIfAbsent("trace", routingTraceForStep(step, request));
        }
        Map<String, Object> semantic = new LinkedHashMap<>(input);
        semantic.remove("purpose");
        List<String> promotedEnvelopes = promotePublishedSchemaArguments(semantic, schema);
        Map<Integer, Object> completedOutputs = new LinkedHashMap<>();
        if (completed != null) {
            completed.forEach((stepId, execution) -> {
                if (stepId != null && execution != null && execution.success()
                    && execution.output() != null) {
                    completedOutputs.put(stepId, execution.output());
                }
            });
        }
        ContextualToolArgumentResolver.Resolution contextual = CONTEXT_ARGUMENT_RESOLVER.resolve(
            new ContextualToolArgumentResolver.Request(
                semantic, schema, originalUserQuery(request), completedOutputs));
        semantic.clear();
        semantic.putAll(contextual.arguments());
        ToolArgumentCompiler.CompilationResult compilation = TOOL_ARGUMENT_COMPILER.compile(semantic, schema);
        if (!compilation.valid()) {
            throw new IllegalStateException(compilation.structuredError(step.toolName(), stringValue(input.get("action"))));
        }
        input.clear();
        input.putAll(compilation.parameters());
        if (contextual.applied()) {
            input.put(CONTEXT_PARAMETER_RECOVERY_KEY, Map.of(
                "recoveredArguments", contextual.recovered(),
                "unresolvedRequiredFields", contextual.unresolvedRequiredFields(),
                "modelCandidatesVerified", contextual.recovered().stream()
                    .filter(ContextualToolArgumentResolver.RecoveredArgument::modelProposed).count()
            ));
            log.info("InterpretationPlan recovered direct-tool parameters from verified context: "
                    + "stepId={}, tool={}, recovered={}, unresolved={}",
                step.id(), step.toolName(), contextual.recovered(), contextual.unresolvedRequiredFields());
        }
        if (!compilation.repairs().isEmpty() || !promotedEnvelopes.isEmpty()) {
            log.info("InterpretationPlan compiled direct tool semantic arguments stepId={} tool={} promotedEnvelopes={} repairs={} compiledKeys={}",
                step.id(), step.toolName(), promotedEnvelopes, compilation.repairs(), input.keySet());
        }
    }

    /**
     * Repairs a common model envelope mismatch without knowing any business tool names.
     * When a direct tool does not publish a model-created object wrapper itself, nested
     * values matching that tool's published JSON Schema are promoted. Top-level values
     * always win and unknown nested values are still discarded by
     * {@link ToolArgumentCompiler}.
     */
    private List<String> promotePublishedSchemaArguments(Map<String, Object> semantic,
                                                         Map<String, Object> schema) {
        if (semantic == null || semantic.isEmpty() || schema == null
            || !(schema.get("properties") instanceof Map<?, ?> rawProperties)) {
            return List.of();
        }
        Map<String, String> publishedSources = new LinkedHashMap<>();
        for (Map.Entry<?, ?> propertyEntry : rawProperties.entrySet()) {
            if (propertyEntry.getKey() == null) {
                continue;
            }
            String propertyName = String.valueOf(propertyEntry.getKey());
            publishedSources.putIfAbsent(canonicalParameterKey(propertyName), propertyName);
            Map<String, Object> property = asStringMap(propertyEntry.getValue());
            for (String source : stringValues(property.get("aliases"))) {
                publishedSources.putIfAbsent(canonicalParameterKey(source), propertyName);
            }
            for (String source : stringValues(property.get("acceptedSources"))) {
                publishedSources.putIfAbsent(canonicalParameterKey(source), propertyName);
            }
        }
        Set<String> publishedFields = rawProperties.keySet().stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(this::canonicalParameterKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> promoted = new ArrayList<>();
        for (Map.Entry<String, Object> candidate : new ArrayList<>(semantic.entrySet())) {
            String wrapper = candidate.getKey();
            if (publishedFields.contains(canonicalParameterKey(wrapper))
                || !(candidate.getValue() instanceof Map<?, ?> nested)) {
                continue;
            }
            Set<String> topLevelFields = semantic.keySet().stream()
                .filter(key -> !Objects.equals(key, wrapper))
                .map(this::canonicalParameterKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean schemaFieldFound = false;
            for (Map.Entry<?, ?> nestedEntry : nested.entrySet()) {
                if (nestedEntry.getKey() == null || nestedEntry.getValue() == null) {
                    continue;
                }
                String nestedKey = String.valueOf(nestedEntry.getKey());
                String canonicalNestedKey = canonicalParameterKey(nestedKey);
                String targetField = publishedSources.get(canonicalNestedKey);
                if (targetField == null) {
                    continue;
                }
                schemaFieldFound = true;
                String canonicalTarget = canonicalParameterKey(targetField);
                if (topLevelFields.add(canonicalTarget)) {
                    semantic.put(targetField, nestedEntry.getValue());
                }
            }
            if (schemaFieldFound) {
                semantic.remove(wrapper);
                promoted.add(wrapper);
            }
        }
        return List.copyOf(promoted);
    }

    private Map<String, Object> publishedInputSchema(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return Map.of();
        }
        Map<String, Object> schema = asStringMap(metadata.getMetadata().get("inputSchema"));
        return schema.get("properties") instanceof Map<?, ?> ? schema : Map.of();
    }

    private Map<String, Object> inputSchemaFromParameters(List<ToolParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", parameter.getType() == null ? "string" : parameter.getType());
            if (parameter.getDefaultValue() != null) {
                property.put("default", parameter.getDefaultValue());
            }
            if (parameter.getEnumValues() != null && parameter.getEnumValues().length > 0) {
                property.put("enum", List.of(parameter.getEnumValues()));
            }
            if (parameter.getMetadata() != null) {
                copyIfPresent(parameter.getMetadata(), property, "format", "aliases", "acceptedSources");
            }
            properties.put(parameter.getName(), property);
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }
        if (properties.isEmpty()) {
            return Map.of();
        }
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", false
        );
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    private Map<Integer, StepExecution> resolveTemplateContractFromMcp(InterpretationPlan.Step step,
                                                                       ExecutionRequest request,
                                                                       Map<Integer, StepExecution> completed,
                                                                       Map<String, Object> input) {
        if (step == null || request == null || input == null || !isTemplateExecutionTool(step.toolName())) {
            return completed;
        }
        String templateHint = canonicalTemplateId(firstValueAtAnyPath(input,
            "$.templateId", "$.template", "$.template_id"));
        if (templateHint != null && !completedTemplateMetadata(completed, templateHint).isEmpty()) {
            return completed;
        }
        if (templateHint == null && uniqueCompletedTemplateForExecutor(step.toolName(), completed) != null) {
            return completed;
        }
        String discoveryTool = templateContractDiscoveryTool(step.toolName(), request.allowedTools());
        if (discoveryTool == null) {
            return completed;
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        Object context = firstMapValue(input, "executionContext", "mcpExecutionContext");
        if (context instanceof Map<?, ?> map) {
            copyNonBlank(map, filters, "assetName", "asset_name", "env", "environment", "databaseType", "dbType");
        }
        String intent = stringValue(firstMapValue(input, "purpose", "intent", "reason"));
        if (intent != null && !intent.isBlank()) {
            filters.put("intent", intent);
        }
        if (templateHint != null) {
            filters.put("templateId", templateHint);
            filters.putIfAbsent("intent", templateHint);
        }
        String targetKind = isLinuxCommandExecuteTool(step.toolName()) ? "host"
            : isHttpRequestExecuteTool(step.toolName()) || isApiTemplateExecuteTool(step.toolName())
                ? "api" : isSqlQueryExecuteTool(step.toolName()) ? "business_database_query" : "database";
        Map<String, Object> discoveryInput = new LinkedHashMap<>();
        discoveryInput.put("candidates", List.of(Map.of("targetKind", targetKind, "confidence", 1.0)));
        discoveryInput.put("finalDecision", targetKind);
        discoveryInput.put("filters", filters);
        discoveryInput.put("limit", 10);
        discoveryInput.put("trace", Map.of(
            "schemaVersion", AgentProtocolCatalog.RUNTIME_ARGUMENT_RESOLUTION,
            "source", "interpretation_plan_runtime",
            "requestId", request.requestId(),
            "stepId", step.id()
        ));
        log.info("InterpretationPlan resolving template argument contract through MCP: traceId={}, stepId={}, executor={}, discoveryTool={}, templateHint={}, filters={}",
            executionTraceId(request), step.id(), step.toolName(), discoveryTool, templateHint, summarize(filters));
        ToolRuntimeExecution resolution = toolRuntimeService.execute(ToolRuntimeRequest.builder()
            .toolName(discoveryTool)
            .runtimeMode("interpretation_plan_argument_resolution")
            .requestId(request.requestId())
            .conversationId(request.conversationId())
            .tenantId(request.tenantId())
            .userId(request.userId())
            .allowedTools(new ArrayList<>(safeList(request.allowedTools())))
            .toolInput(ToolInput.builder()
                .requestId(request.requestId())
                .conversationId(request.conversationId())
                .userId(request.userId())
                .parameters(discoveryInput)
                .build())
            .attributes(Map.of(
                "argumentResolution", true,
                "executorTool", step.toolName(),
                "interpretationPlanStepId", step.id()
            ))
            .build());
        if (resolution == null || resolution.output() == null || !resolution.output().isSuccess()) {
            throw new IllegalStateException("TEMPLATE_CONTRACT_RESOLUTION_FAILED: MCP contract query failed for "
                + step.toolName() + ": " + (resolution == null || resolution.output() == null
                ? "no result" : resolution.output().getErrorMessage()));
        }
        Map<String, Object> selected = selectResolvedTemplate(resolution.output().getData(), templateHint, step.toolName());
        if (selected.isEmpty()) {
            throw new IllegalStateException("TEMPLATE_CONTRACT_RESOLUTION_FAILED: no matching executable template "
                + "was returned for " + (templateHint == null ? step.toolName() : templateHint));
        }
        String resolvedTemplateId = canonicalTemplateId(selected);
        input.put("templateId", resolvedTemplateId);
        input.put("template", resolvedTemplateId);
        Map<Integer, StepExecution> contextWithResolution = new LinkedHashMap<>(completed == null ? Map.of() : completed);
        contextWithResolution.put(Integer.MIN_VALUE + (step.id() == null ? 0 : step.id()), new StepExecution(
            Integer.MIN_VALUE + (step.id() == null ? 0 : step.id()),
            "runtime_contract_resolution",
            discoveryTool,
            true,
            Map.of("templates", List.of(selected)),
            null,
            resolution,
            null,
            0L
        ));
        log.info("InterpretationPlan template argument contract resolved: traceId={}, stepId={}, executor={}, discoveryTool={}, templateId={}",
            executionTraceId(request), step.id(), step.toolName(), discoveryTool, resolvedTemplateId);
        return contextWithResolution;
    }

    private String templateContractDiscoveryTool(String executorTool, List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return null;
        }
        for (String tool : allowedTools) {
            String semantic = toolSemanticKey(tool);
            boolean matches = isLinuxCommandExecuteTool(executorTool)
                ? semantic.contains("ssh") && (semantic.endsWith("template_query") || semantic.endsWith("template_search"))
                : isApiTemplateExecuteTool(executorTool)
                ? semantic.contains("api")
                    && (semantic.endsWith("template_query") || semantic.endsWith("template_search"))
                : isHttpRequestExecuteTool(executorTool)
                ? semantic.contains("http_endpoint")
                    && (semantic.endsWith("template_query") || semantic.endsWith("template_search"))
                : (semantic.contains("database") || semantic.contains("sql") || semantic.contains("business_query"))
                    && (semantic.endsWith("template_query") || semantic.endsWith("template_search"));
            if (matches && !sameToolName(tool, executorTool)) {
                return tool;
            }
        }
        return null;
    }

    private int structuredObservationCount(Object output, int depth) {
        if (output == null || depth > 8) return 0;
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) return structuredObservationCount(normalized, depth + 1);
        if (!(output instanceof Map<?, ?> map)) return 0;
        Integer count = integerValue(firstMapValue(map, "structuredObservationCount", "structured_observation_count"));
        if (count != null && count > 0) return count;
        Object datasets = firstMapValue(map, "structuredData", "structured_data");
        if (datasets instanceof List<?> values) {
            int total = 0;
            for (Object value : values) {
                if (value instanceof Map<?, ?> dataset) {
                    Integer datasetCount = integerValue(firstMapValue(dataset, "count", "rowCount", "row_count"));
                    if (datasetCount != null && datasetCount > 0) total += datasetCount;
                }
            }
            if (total > 0) return total;
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "output")) {
            int nested = structuredObservationCount(firstMapValue(map, key), depth + 1);
            if (nested > 0) return nested;
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> selectResolvedTemplate(Object output, String templateHint, String executorTool) {
        Map<String, Object> first = Map.of();
        for (Object candidate : templateCandidates(output)) {
            if (!(candidate instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) map);
            String id = canonicalTemplateId(template);
            if (id == null) {
                continue;
            }
            if (templateHint != null && templateHint.equalsIgnoreCase(id)) {
                return template;
            }
            if (first.isEmpty() && (templateExecutorMatches(template, executorTool)
                || firstValueAtAnyPath(template, "$.executionTool", "$.sqlExecutionBinding.toolName") == null)) {
                first = template;
            }
        }
        return templateHint == null ? first : Map.of();
    }

    private void copyNonBlank(Map<?, ?> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                target.put(key, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void hydrateExecutionContextFromTemplateMetadata(InterpretationPlan.Step step,
                                                              Map<Integer, StepExecution> completed,
                                                              Map<String, Object> input) {
        if (step == null || input == null || completed == null || completed.isEmpty()
            || !isTemplateExecutionTool(step.toolName())) {
            return;
        }
        Object existing = firstMapValue(input, "executionContext", "mcpExecutionContext");
        Map<String, Object> executionContext = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Object templateIdValue = firstValueAtAnyPath(input, "$.templateId", "$.template", "$.template_id");
        if (templateIdValue == null || String.valueOf(templateIdValue).isBlank()) {
            return;
        }
        Map<String, Object> template = completedTemplateMetadata(completed, String.valueOf(templateIdValue));
        Object contextValue = firstValueAtAnyPath(template,
            "$.sqlExecutionBinding.executionContext",
            "$.executionBinding.executionContext",
            "$.executionContext",
            "$.execution.executionContext");
        if (!(contextValue instanceof Map<?, ?> contextMap) || contextMap.isEmpty()) {
            return;
        }
        contextMap.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(value).isBlank()) {
                // Discovery metadata is the authorized routing contract. It must fill or
                // replace partial model context (for example env-only input) for every
                // template executor, independent of the business protocol behind it.
                executionContext.put(String.valueOf(key), value);
            }
        });
        if (!executionContext.isEmpty()) {
            input.put("executionContext", executionContext);
        }
    }

    @SuppressWarnings("unchecked")
    private TemplateExecutorInvocation templateExecutorInvocation(InterpretationPlan.Step step,
                                                                  Map<Integer, StepExecution> completed,
                                                                  Map<String, Object> input,
                                                                  List<String> allowedTools) {
        if (step == null || input == null || completed == null || completed.isEmpty()
            || isExecutionContextTool(step.toolName())) {
            return null;
        }
        Map<String, Object> template = completedTemplateMetadataByToolName(completed, step.toolName());
        if (template.isEmpty()) {
            return null;
        }
        String executor = stringValue(firstValueAtAnyPath(template,
            "$.sqlExecutionBinding.toolName",
            "$.executionBinding.toolName",
            "$.execution.executorTool",
            "$.execution.toolName",
            "$.execution.executionTool",
            "$.executionTool"));
        String executionTool = resolveExecutionToolName(executor, allowedTools);
        if (executionTool == null || executionTool.isBlank()) {
            throw new IllegalStateException("TEMPLATE_EXECUTOR_NOT_AVAILABLE: template " + step.toolName()
                + " was selected but no declared executor tool is available");
        }
        Map<String, Object> arguments = new LinkedHashMap<>(input);
        String templateId = stringValue(firstValueAtAnyPath(template,
            "$.sqlExecutionBinding.templateId",
            "$.executionBinding.templateId",
            "$.templateId",
            "$.id",
            "$.code",
            "$.template",
            "$.execution.template",
            "$.execution.callTool",
            "$.mcpToolName"));
        if (templateId != null && !templateId.isBlank()) {
            arguments.putIfAbsent("templateId", templateId);
            arguments.putIfAbsent("template", templateId);
        }
        Object existingContext = firstMapValue(arguments, "executionContext", "mcpExecutionContext");
        Map<String, Object> executionContext = existingContext instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Object contextValue = firstValueAtAnyPath(template,
            "$.sqlExecutionBinding.executionContext",
            "$.executionBinding.executionContext",
            "$.execution.executionContext",
            "$.executionContext");
        if (contextValue instanceof Map<?, ?> contextMap) {
            contextMap.forEach((key, value) -> {
                if (key != null && value != null && !String.valueOf(value).isBlank()) {
                    executionContext.putIfAbsent(String.valueOf(key), value);
                }
            });
        }
        if (!executionContext.isEmpty()) {
            arguments.put("executionContext", executionContext);
        }
        return new TemplateExecutorInvocation(executionTool, arguments);
    }

    /**
     * Compiles a scalar executor step shared by multiple diagnostic checks into one formal,
     * sequential batch after template discovery has returned the authorized templates.
     *
     * <p>This is deliberately metadata driven: no database vendor or template code is built into
     * Runtime. Checks are matched to discovered template identifiers/capabilities and every child
     * call keeps the exact check id as its auditable call id.</p>
     */
    @SuppressWarnings("unchecked")
    private TemplateExecutorInvocation diagnosticBatchInvocation(
        InterpretationPlan.Step step,
        InterpretationPlan plan,
        Map<Integer, StepExecution> completed,
        Map<String, Object> input,
        List<String> allowedTools,
        com.chatchat.agents.tool.ToolRegistry toolRegistry
    ) {
        if (step == null || step.id() == null || plan == null || plan.plan() == null
            || plan.plan().diagnosticProfile() == null
            || completed == null || completed.isEmpty()) {
            return null;
        }
        List<InterpretationPlan.DiagnosticCheck> profileChecks =
            plan.plan().diagnosticProfile().checks() == null
                ? List.of()
                : plan.plan().diagnosticProfile().checks();
        List<InterpretationPlan.DiagnosticCheck> checks = profileChecks.stream()
            .filter(Objects::nonNull)
            .filter(check -> !Boolean.FALSE.equals(check.required()))
            .filter(check -> check.stepIds() != null && check.stepIds().contains(step.id()))
            .sorted(java.util.Comparator.comparingInt(check ->
                check.priority() == null ? Integer.MAX_VALUE : check.priority()))
            .toList();
        if (checks.size() < 2) {
            return null;
        }
        if (shouldUseReviewedTemplateBatch(checks, completed)) {
            // A coarse diagnostic check may expand into several model-reviewed templates.
            // Preserve that authorized set instead of guessing a one-to-one semantic match.
            return null;
        }

        List<Map<String, Object>> templates = new ArrayList<>();
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isTemplateDiscoveryTool(execution.toolName())) {
                continue;
            }
            for (Object item : templateCandidates(execution.output())) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) map);
                String executor = templateExecutorTool(template);
                String resolvedExecutor = resolveExecutionToolName(executor, allowedTools);
                if (resolvedExecutor != null && batchCapable(resolvedExecutor, toolRegistry)) {
                    templates.add(template);
                }
            }
        }
        // A diagnostic profile describes evidence coverage; it does not imply that one
        // executor step must become a batch. Compile a batch only when discovery supplied
        // enough authorized contracts to represent every required check. Otherwise keep the
        // normal scalar template invocation selected by the plan binding.
        if (templates.size() < checks.size()) {
            return null;
        }

        Map<String, Object> batchInput = new LinkedHashMap<>(input == null ? Map.of() : input);
        hydrateDiagnosticBatchAssetContext(completed, batchInput);
        String targetAssetId = contextText(batchInput, "assetId", "asset_id");
        if (targetAssetId == null || targetAssetId.isBlank()) {
            throw new IllegalStateException(
                "DIAGNOSTIC_CANONICAL_ASSET_ID_REQUIRED: authorized diagnostic batch compilation "
                    + "requires the canonical asset id returned by asset or template discovery");
        }
        templates.removeIf(template -> {
            String templateAssetId = templateAssetId(template);
            return templateAssetId != null && !targetAssetId.equals(templateAssetId);
        });
        if (templates.size() < checks.size()) {
            return null;
        }

        Map<Integer, Integer> templateAssignments = diagnosticTemplateAssignments(
            checks,
            templates,
            resolvedDiagnosticTemplateHints(step.input(), input),
            resolvedDiagnosticCallContexts(step.input(), input));
        if (templateAssignments.size() != checks.size()) {
            List<String> unmatchedCheckIds = java.util.stream.IntStream.range(0, checks.size())
                .filter(index -> !templateAssignments.containsKey(index))
                .mapToObj(index -> checks.get(index).checkId())
                .filter(Objects::nonNull)
                .toList();
            Map<String, String> assignedTemplateIds = new LinkedHashMap<>();
            templateAssignments.forEach((checkIndex, templateIndex) -> {
                if (checkIndex >= 0 && checkIndex < checks.size()
                    && templateIndex >= 0 && templateIndex < templates.size()) {
                    assignedTemplateIds.put(
                        firstText(checks.get(checkIndex).checkId(), "check-" + checkIndex),
                        firstText(canonicalTemplateId(templates.get(templateIndex)),
                            "template-" + templateIndex));
                }
            });
            log.warn("Diagnostic template coverage mismatch: unmatchedCheckIds={}, "
                    + "assignedTemplateIds={}, candidateTemplateIds={}",
                unmatchedCheckIds, assignedTemplateIds,
                templates.stream().map(this::canonicalTemplateId).filter(Objects::nonNull).toList());
            throw new IllegalStateException(
                "DIAGNOSTIC_TEMPLATE_COVERAGE_MISMATCH: governed template candidates do not "
                    + "semantically cover required checks " + unmatchedCheckIds
                    + "; template discovery must be retried with the missing check intent");
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        String outerTool = null;
        for (int checkIndex = 0; checkIndex < checks.size(); checkIndex++) {
            InterpretationPlan.DiagnosticCheck check = checks.get(checkIndex);
            Integer matchIndex = templateAssignments.get(checkIndex);
            if (matchIndex == null) {
                continue;
            }
            Map<String, Object> template = templates.get(matchIndex);
            String templateId = canonicalTemplateId(template);
            String childTool = resolveExecutionToolName(templateExecutorTool(template), allowedTools);
            Map<String, Object> arguments = diagnosticBatchArguments(batchInput, template, templateId);
            if (templateId == null || childTool == null || !requiredTemplateParametersSatisfied(template, arguments)) {
                continue;
            }
            if (outerTool == null) {
                outerTool = childTool;
            }
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("callId", check.checkId());
            call.put("toolName", childTool);
            call.put("arguments", arguments);
            Boolean emptyResultIsSuccess = booleanObject(firstValueAtAnyPath(template,
                "$.emptyResultIsSuccess",
                "$.resultPolicy.emptyResultIsSuccess",
                "$.evidencePolicy.emptyResultIsSuccess"));
            if (emptyResultIsSuccess != null) {
                call.put("emptyResultIsSuccess", emptyResultIsSuccess);
            }
            List<String> requiredFields = stringValues(firstValueAtAnyPath(template,
                "$.requiredFields",
                "$.required_fields",
                "$.outputSchema.required",
                "$.output_schema.required",
                "$.resultPolicy.requiredFields",
                "$.evidencePolicy.requiredFields",
                "$.qualityPolicy.requiredFields"));
            if (!requiredFields.isEmpty()) {
                call.put("requiredFields", requiredFields);
            }
            List<String> requiredMetrics = stringValues(firstValueAtAnyPath(template,
                "$.requiredMetrics",
                "$.required_metrics",
                "$.evidencePolicy.requiredMetrics",
                "$.qualityPolicy.requiredMetrics"));
            if (requiredMetrics.isEmpty()) {
                requiredMetrics = requiredFields;
            }
            if (!requiredMetrics.isEmpty()) {
                call.put("requiredMetrics", requiredMetrics);
            }
            Object purpose = firstValueAtAnyPath(template,
                "$.purpose",
                "$.diagnosticPurpose",
                "$.evidencePolicy.purpose",
                "$.qualityPolicy.purpose");
            if (purpose != null && !String.valueOf(purpose).isBlank()) {
                call.put("purpose", String.valueOf(purpose));
            }
            Boolean healthCapability = booleanObject(firstValueAtAnyPath(template,
                "$.healthCapability",
                "$.health_capability",
                "$.evidencePolicy.healthCapability",
                "$.qualityPolicy.healthCapability"));
            if (healthCapability != null) {
                call.put("healthCapability", healthCapability);
            }
            Object timeSemantics = firstValueAtAnyPath(template,
                "$.timeSemantics",
                "$.time_semantics",
                "$.evidencePolicy.timeSemantics",
                "$.qualityPolicy.timeSemantics");
            if (timeSemantics != null && !String.valueOf(timeSemantics).isBlank()) {
                call.put("timeSemantics", String.valueOf(timeSemantics));
            }
            List<String> requiresContext = stringValues(firstValueAtAnyPath(template,
                "$.requiresContext",
                "$.requires_context",
                "$.evidencePolicy.requiresContext",
                "$.qualityPolicy.requiresContext"));
            if (!requiresContext.isEmpty()) {
                call.put("requiresContext", requiresContext);
            }
            Integer freshnessMaxAgeSeconds = integerValue(firstValueAtAnyPath(template,
                "$.freshnessMaxAgeSeconds",
                "$.freshness_max_age_seconds",
                "$.evidencePolicy.freshnessMaxAgeSeconds",
                "$.qualityPolicy.freshnessMaxAgeSeconds"));
            if (freshnessMaxAgeSeconds != null && freshnessMaxAgeSeconds >= 0) {
                call.put("freshnessMaxAgeSeconds", freshnessMaxAgeSeconds);
            }
            calls.add(call);
        }
        if (calls.size() != checks.size() || outerTool == null) {
            return null;
        }
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", "diagnostic-step-" + step.id());
        batch.put("executionMode", "SEQUENTIAL");
        batch.put("stopOnFailure", false);
        batch.put("calls", calls);
        Set<String> compiledCheckIds = calls.stream()
            .map(call -> stringValue(call.get("callId")))
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> missingCheckIds = checks.stream()
            .map(InterpretationPlan.DiagnosticCheck::checkId)
            .filter(checkId -> checkId != null && !compiledCheckIds.contains(checkId))
            .toList();
        log.info("InterpretationPlan compiled final diagnostic batch: stepId={}, assetId={}, "
                + "declaredChecks={}, compiledCalls={}, callIds={}, templateIds={}, missingCheckIds={}",
            step.id(), targetAssetId, checks.size(), calls.size(),
            calls.stream().map(call -> call.get("callId")).toList(),
            calls.stream()
                .map(call -> firstValueAtAnyPath(call, "$.arguments.templateId"))
                .toList(),
            missingCheckIds);
        return new TemplateExecutorInvocation(outerTool, batch);
    }

    private boolean shouldUseReviewedTemplateBatch(
        List<InterpretationPlan.DiagnosticCheck> checks,
        Map<Integer, StepExecution> completed
    ) {
        StepExecution reviewedSelection = reviewedTemplateSelectionExecution(completed);
        return reviewedSelection != null
            && Boolean.TRUE.equals(reviewedSelection.metadata().get("semanticCandidateReviewSatisfied"))
            && templateCandidates(reviewedSelection.output()).size()
                > (checks == null ? 0 : checks.size());
    }

    /**
     * Compiles every template admitted by successful governed discovery into a Runtime-owned
     * failure-isolated batch. The plan does not have to declare batch transport: a scalar
     * {@code templates[0]} binding is upgraded here so model drift cannot silently omit the rest
     * of the discovered set. Runtime still never executes an id that is absent from MCP output.
     */
    private TemplateExecutorInvocation reviewedTemplateBatchInvocation(
        InterpretationPlan.Step step,
        Map<Integer, StepExecution> completed,
        Map<String, Object> input,
        List<String> allowedTools,
        com.chatchat.agents.tool.ToolRegistry toolRegistry
    ) {
        if (!runtimeOwnedReviewedTemplateBatch(step, completed)) {
            return null;
        }
        List<String> selectedIds = reviewedSelectedTemplateIds(completed);
        Map<String, Object> batchInput = new LinkedHashMap<>(input == null ? Map.of() : input);
        mergeReviewedTemplateExecutionInputChanges(completed, step.toolName(), batchInput);
        List<Map<String, Object>> calls = new ArrayList<>();
        String outerTool = null;
        for (String templateId : selectedIds) {
            Map<String, Object> template = completedTemplateMetadata(completed, templateId);
            String declaredExecutor = firstText(templateExecutorTool(template), step.toolName());
            String childTool = resolveExecutionToolName(declaredExecutor, allowedTools);
            Map<String, Object> arguments = diagnosticBatchArguments(batchInput, template, templateId);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("callId", templateId);
            call.put("toolName", firstText(childTool, step.toolName()));
            call.put("arguments", arguments);
            if (template.isEmpty()) {
                call.put("preflightErrorCode", "ADMITTED_TEMPLATE_METADATA_UNAVAILABLE");
                call.put("preflightMessage", "The admitted template metadata was unavailable at execution preflight");
            } else if (childTool == null || !batchCapable(childTool, toolRegistry)) {
                call.put("preflightErrorCode", "TEMPLATE_EXECUTOR_UNAVAILABLE");
                call.put("preflightMessage", "No authorized batch-capable executor was available for the admitted template");
            } else {
                List<String> missingParameters = missingRequiredTemplateParameters(template, arguments);
                if (!missingParameters.isEmpty()) {
                    call.put("preflightErrorCode", "TEMPLATE_REQUIRED_PARAMETERS_MISSING");
                    call.put("preflightMessage", "Required template parameters are unavailable: "
                        + missingParameters);
                }
                if (outerTool == null) {
                    outerTool = childTool;
                }
            }
            calls.add(call);
        }
        if (calls.size() < 2) {
            throw new IllegalStateException(
                "REVIEWED_TEMPLATE_BATCH_COMPILATION_FAILED: no complete admitted template set "
                    + "was available; admitted=" + selectedIds);
        }
        outerTool = firstText(outerTool, resolveExecutionToolName(step.toolName(), allowedTools));
        if (outerTool == null || !batchCapable(outerTool, toolRegistry)) {
            throw new IllegalStateException(
                "REVIEWED_TEMPLATE_BATCH_EXECUTOR_UNAVAILABLE: no authorized batch transport exists");
        }
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", "reviewed-template-step-" + step.id());
        batch.put("executionMode", firstText(stringValue(batchInput.get("executionMode")), "SEQUENTIAL"));
        batch.put("stopOnFailure", false);
        batch.put("calls", calls);
        log.info("InterpretationPlan compiled reviewed template batch with terminal preflight coverage: "
                + "stepId={}, selectedCount={}, compiledCalls={}, blockedCalls={}, templateIds={}",
            step.id(), selectedIds.size(), calls.size(),
            calls.stream().filter(call -> call.containsKey("preflightErrorCode")).count(),
            calls.stream().map(call -> call.get("callId")).toList());
        return new TemplateExecutorInvocation(outerTool, batch);
    }

    private boolean batchCapable(String toolName,
                                 com.chatchat.agents.tool.ToolRegistry toolRegistry) {
        ToolMetadata metadata = toolName == null || toolRegistry == null
            ? null : toolRegistry.getToolMetadata(toolName);
        return ToolCallBatchSchema.supports(toolName, metadata);
    }

    private List<String> reviewedSelectedTemplateIds(Map<Integer, StepExecution> completed) {
        StepExecution selection = reviewedTemplateSelectionExecution(completed);
        if (selection == null) {
            return List.of();
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Object candidate : templateCandidates(selection.output())) {
            if (!(candidate instanceof Map<?, ?> rawTemplate)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) rawTemplate);
            String id = canonicalTemplateId(template);
            String canonical = canonicalTemplateId(id);
            if (canonical != null) {
                selected.add(canonical);
            }
        }
        return List.copyOf(selected);
    }

    private StepExecution reviewedTemplateSelectionExecution(Map<Integer, StepExecution> completed) {
        if (completed == null) {
            return null;
        }
        StepExecution latest = null;
        for (StepExecution execution : completed.values()) {
            if (execution != null && execution.success() && isTemplateDiscoveryTool(execution.toolName())
                && templateCandidates(execution.output()).stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(this::canonicalTemplateId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .limit(2)
                    .count() >= 2) {
                latest = execution;
            }
        }
        return latest;
    }

    @SuppressWarnings("unchecked")
    private void mergeReviewedTemplateExecutionInputChanges(Map<Integer, StepExecution> completed,
                                                            String executorTool,
                                                            Map<String, Object> target) {
        if (completed == null || target == null) {
            return;
        }
        StepExecution selection = reviewedTemplateSelectionExecution(completed);
        Object rawActions = selection == null || selection.metadata() == null
            ? null : selection.metadata().get("nextActions");
        if (rawActions instanceof Iterable<?> actions) {
            for (Object item : actions) {
                if (!(item instanceof Map<?, ?> rawAction)) {
                    continue;
                }
                Map<String, Object> action = new LinkedHashMap<>((Map<String, Object>) rawAction);
                String tool = stringValue(firstMapValue(action, "tool", "toolName", "tool_name"));
                if (tool == null || (!sameToolName(tool, executorTool)
                    && !(isTemplateExecutionTool(tool) && isTemplateExecutionTool(executorTool)))) {
                    continue;
                }
                Object rawChanges = firstMapValue(action, "input_changes", "inputChanges");
                if (!(rawChanges instanceof Map<?, ?> changes)) {
                    continue;
                }
                changes.forEach((key, value) -> {
                    if (key != null && value != null
                        && !"selected_template_ids".equals(String.valueOf(key))
                        && !"selectedTemplateIds".equals(String.valueOf(key))) {
                        target.put(String.valueOf(key), value);
                    }
                });
            }
        }
    }

    private boolean hasRecoverableReviewedTemplateBatchDownstream(InterpretationPlan plan,
                                                                   Integer failedStepId,
                                                                   Map<Integer, StepExecution> completed) {
        if (plan == null || failedStepId == null || plan.steps() == null) {
            return false;
        }
        return plan.steps().stream()
            .filter(Objects::nonNull)
            .filter(step -> safeIntegerList(step.dependsOn()).contains(failedStepId))
            .anyMatch(step -> runtimeOwnedReviewedTemplateBatch(step, completed));
    }

    @SuppressWarnings("unchecked")
    private void hydrateDiagnosticBatchAssetContext(Map<Integer, StepExecution> completed,
                                                    Map<String, Object> input) {
        if (completed == null || completed.isEmpty() || input == null) {
            return;
        }
        Object existing = firstMapValue(input, "executionContext", "mcpExecutionContext");
        Map<String, Object> context = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Map<String, Object> assetDiscoveryContext = firstCompletedAssetExecutionContext(completed);
        Map<String, Object> templateDiscoveryContext =
            firstCompletedTemplateAssetExecutionContext(completed);
        String assetDiscoveryId = stringValue(assetDiscoveryContext.get("assetId"));
        String templateDiscoveryId = stringValue(templateDiscoveryContext.get("assetId"));
        if (assetDiscoveryId != null && templateDiscoveryId != null
            && !assetDiscoveryId.equals(templateDiscoveryId)) {
            throw new IllegalStateException(
                "DIAGNOSTIC_ASSET_CONTEXT_MISMATCH: asset discovery and authorized template "
                    + "discovery resolved different canonical assets");
        }
        Map<String, Object> discovered = assetDiscoveryContext.isEmpty()
            ? templateDiscoveryContext
            : assetDiscoveryContext;
        String canonicalAssetId = stringValue(discovered.get("assetId"));
        String suppliedAssetId = contextText(Map.of("executionContext", context), "assetId", "asset_id");
        if (canonicalAssetId != null && suppliedAssetId != null
            && !canonicalAssetId.equals(suppliedAssetId)) {
            throw new IllegalStateException(
                "DIAGNOSTIC_ASSET_CONTEXT_MISMATCH: planned executionContext.assetId does not "
                    + "match the canonical asset returned by discovery");
        }
        discovered.forEach((key, value) -> putIfAbsentOrPlaceholder(context, key, value));
        if (canonicalAssetId != null) {
            context.put("assetId", canonicalAssetId);
        }
        if (!context.isEmpty()) {
            input.put("executionContext", context);
        }
    }

    private Map<String, Object> firstCompletedTemplateAssetExecutionContext(
        Map<Integer, StepExecution> completed
    ) {
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isTemplateDiscoveryTool(execution.toolName())) {
                continue;
            }
            Map<String, Object> context = templateDiscoveryAssetExecutionContext(execution.output());
            if (!context.isEmpty()) {
                return context;
            }
        }
        return Map.of();
    }

    private Map<String, Object> templateDiscoveryAssetExecutionContext(Object output) {
        Object scoped = firstValueAtAnyPath(output, "$.queryIr.asset.scoped");
        if (!Boolean.TRUE.equals(booleanValue(scoped))) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        putIfPresent(context, "assetId", firstValueAtAnyPath(output,
            "$.queryIr.asset.selected.id",
            "$.queryIr.asset.selected.assetId"));
        putIfPresent(context, "assetName", firstValueAtAnyPath(output,
            "$.queryIr.asset.selected.name",
            "$.queryIr.asset.selected.displayName",
            "$.queryIr.asset.selected.title"));
        putIfPresent(context, "assetDisplayName", firstValueAtAnyPath(output,
            "$.queryIr.asset.selected.title",
            "$.queryIr.asset.selected.displayName",
            "$.queryIr.asset.selected.name"));
        putIfPresent(context, "assetToolName", firstValueAtAnyPath(output,
            "$.queryIr.asset.selected.toolName",
            "$.queryIr.asset.selected.tool_name"));
        putIfPresent(context, "env", firstValueAtAnyPath(output,
            "$.queryIr.asset.selected.environment",
            "$.queryIr.asset.selected.env"));
        return context.containsKey("assetId") ? context : Map.of();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target != null && key != null && value != null && !String.valueOf(value).isBlank()) {
            target.put(key, String.valueOf(value));
        }
    }

    private String templateExecutorTool(Map<String, Object> template) {
        return stringValue(firstValueAtAnyPath(template,
            "$.sqlExecutionBinding.toolName",
            "$.executionBinding.toolName",
            "$.parameterContract.executionTool",
            "$.execution.executorTool",
            "$.execution.toolName",
            "$.execution.executionTool",
            "$.executionTool",
            "$.invocationExample.tool"));
    }

    private String templateAssetId(Map<String, Object> template) {
        return stringValue(firstValueAtAnyPath(template,
            "$.assetId",
            "$.asset.id",
            "$.sqlExecutionBinding.assetId",
            "$.sqlExecutionBinding.executionContext.assetId",
            "$.executionBinding.assetId",
            "$.executionBinding.executionContext.assetId",
            "$.executionContext.assetId",
            "$.execution.executionContext.assetId"));
    }

    private String contextText(Map<String, Object> input, String... keys) {
        Object context = firstMapValue(input, "executionContext", "mcpExecutionContext");
        if (context instanceof Map<?, ?> map) {
            for (String key : keys) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        }
        return null;
    }

    private Map<Integer, Integer> diagnosticTemplateAssignments(
        List<InterpretationPlan.DiagnosticCheck> checks,
        List<Map<String, Object>> templates
    ) {
        return diagnosticTemplateAssignments(checks, templates, Map.of());
    }

    private Map<Integer, Integer> diagnosticTemplateAssignments(
        List<InterpretationPlan.DiagnosticCheck> checks,
        List<Map<String, Object>> templates,
        Map<String, String> templateHints
    ) {
        return diagnosticTemplateAssignments(checks, templates, templateHints, Map.of());
    }

    private Map<Integer, Integer> diagnosticTemplateAssignments(
        List<InterpretationPlan.DiagnosticCheck> checks,
        List<Map<String, Object>> templates,
        Map<String, String> templateHints,
        Map<String, String> callContexts
    ) {
        List<DiagnosticTemplateMatch> candidates = new ArrayList<>();
        for (int checkIndex = 0; checkIndex < checks.size(); checkIndex++) {
            InterpretationPlan.DiagnosticCheck check = checks.get(checkIndex);
            String templateHint = templateHints.get(check.checkId());
            String callContext = callContexts.getOrDefault(check.checkId(), "");
            for (int templateIndex = 0; templateIndex < templates.size(); templateIndex++) {
                Map<String, Object> template = templates.get(templateIndex);
                String candidateId = canonicalTemplateId(template);
                boolean exactHint = templateHint != null && candidateId != null
                    && templateHint.equalsIgnoreCase(candidateId);
                int semanticScore = diagnosticSemanticScore(check, template, callContext);
                int score = exactHint && semanticScore > 0
                    ? 1_000_000 + semanticScore
                    : semanticScore;
                if (score > 0) {
                    candidates.add(new DiagnosticTemplateMatch(checkIndex, templateIndex, score));
                }
            }
        }
        candidates.sort(java.util.Comparator
            .comparingInt(DiagnosticTemplateMatch::score).reversed()
            .thenComparingInt(DiagnosticTemplateMatch::checkIndex)
            .thenComparingInt(DiagnosticTemplateMatch::templateIndex));
        Set<Integer> assignedChecks = new LinkedHashSet<>();
        Set<Integer> assignedTemplates = new LinkedHashSet<>();
        Map<Integer, Integer> assignments = new LinkedHashMap<>();
        for (DiagnosticTemplateMatch candidate : candidates) {
            if (assignedChecks.contains(candidate.checkIndex())
                || assignedTemplates.contains(candidate.templateIndex())) {
                continue;
            }
            assignedChecks.add(candidate.checkIndex());
            assignedTemplates.add(candidate.templateIndex());
            assignments.put(candidate.checkIndex(), candidate.templateIndex());
        }
        return assignments;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> diagnosticTemplateHints(Map<String, Object> stepInput) {
        if (stepInput == null || !(stepInput.get("calls") instanceof Iterable<?> calls)) {
            return Map.of();
        }
        Map<String, String> hints = new LinkedHashMap<>();
        for (Object item : calls) {
            if (!(item instanceof Map<?, ?> rawCall)) continue;
            Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) rawCall);
            String callId = firstText(
                stringValue(firstMapValue(call, "callId", "call_id", "checkId", "check_id")), null);
            Object rawArguments = firstMapValue(call, "arguments", "input");
            Map<String, Object> arguments = rawArguments instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : Map.of();
            String templateId = canonicalTemplateId(firstText(
                stringValue(firstMapValue(arguments, "templateId", "template_id", "template")),
                stringValue(firstMapValue(call, "templateId", "template_id", "template"))));
            if (callId != null && templateId != null) hints.putIfAbsent(callId, templateId);
        }
        return Map.copyOf(hints);
    }

    /**
     * Uses the post-binding executor input as the authoritative source of
     * template hints. The original plan input may still contain planner
     * placeholders, so it is retained only as a fallback. This rule applies to
     * every registered template executor and does not depend on check names or
     * business-specific template identifiers.
     */
    private Map<String, String> resolvedDiagnosticTemplateHints(
        Map<String, Object> plannedInput,
        Map<String, Object> resolvedInput
    ) {
        Map<String, String> hints = new LinkedHashMap<>(diagnosticTemplateHints(plannedInput));
        diagnosticTemplateHints(resolvedInput).forEach(hints::put);
        return Map.copyOf(hints);
    }

    private Map<String, String> resolvedDiagnosticCallContexts(
        Map<String, Object> plannedInput,
        Map<String, Object> resolvedInput
    ) {
        Map<String, String> contexts = new LinkedHashMap<>(diagnosticCallContexts(plannedInput));
        diagnosticCallContexts(resolvedInput).forEach(contexts::put);
        return Map.copyOf(contexts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> diagnosticCallContexts(Map<String, Object> stepInput) {
        if (stepInput == null || !(stepInput.get("calls") instanceof Iterable<?> calls)) {
            return Map.of();
        }
        Map<String, String> contexts = new LinkedHashMap<>();
        for (Object item : calls) {
            if (!(item instanceof Map<?, ?> rawCall)) continue;
            Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) rawCall);
            String callId = firstText(
                stringValue(firstMapValue(call, "callId", "call_id", "checkId", "check_id")), null);
            if (callId == null) continue;
            List<Object> values = new ArrayList<>();
            values.add(callId);
            for (String key : List.of(
                "purpose", "description", "reason", "requiredMetrics", "required_metrics",
                "requiredFields", "required_fields", "healthCapability", "health_capability"
            )) {
                Object value = call.get(key);
                if (value != null) values.add(value);
            }
            Object rawArguments = firstMapValue(call, "arguments", "input");
            if (rawArguments instanceof Map<?, ?> arguments) {
                for (String key : List.of("purpose", "description", "reason")) {
                    Object value = arguments.get(key);
                    if (value != null) values.add(value);
                }
            }
            contexts.put(callId, values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" ")));
        }
        return Map.copyOf(contexts);
    }

    private String diagnosticTemplateIdentity(Map<String, Object> template) {
        List<Object> values = java.util.Arrays.asList(
            canonicalTemplateId(template),
            firstValueAtAnyPath(template, "$.name"),
            firstValueAtAnyPath(template, "$.displayName"),
            firstValueAtAnyPath(template, "$.capability"),
            firstValueAtAnyPath(template, "$.diagnosticCapability"),
            firstValueAtAnyPath(template, "$.purpose"),
            firstValueAtAnyPath(template, "$.diagnosticPurpose"),
            firstValueAtAnyPath(template, "$.description"),
            firstValueAtAnyPath(template, "$.category"),
            firstValueAtAnyPath(template, "$.operationType"),
            firstValueAtAnyPath(template, "$.keywords"),
            firstValueAtAnyPath(template, "$.tags")
        );
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.joining(" "));
    }

    private int diagnosticSemanticScore(InterpretationPlan.DiagnosticCheck check, String templateIdentity) {
        return diagnosticSemanticScore(check, templateIdentity, false);
    }

    /**
     * Scores discovered templates without treating descriptive prose as an authoritative binding.
     * A stable template identifier match is weighted above names/descriptions; otherwise at least
     * two semantic terms or an exact capability/dimension phrase must agree. This prevents a broad
     * word such as "process" in an IO template description from satisfying a process-inventory
     * check while still allowing identifiers such as CHECK_MEMORY to match a memory dimension.
     */
    private int diagnosticSemanticScore(InterpretationPlan.DiagnosticCheck check,
                                        Map<String, Object> template) {
        return diagnosticSemanticScore(check, template, "");
    }

    private int diagnosticSemanticScore(InterpretationPlan.DiagnosticCheck check,
                                        Map<String, Object> template,
                                        String callContext) {
        if (check == null || template == null || template.isEmpty()) {
            return 0;
        }
        String canonicalId = firstText(canonicalTemplateId(template), "");
        String fullIdentity = diagnosticTemplateIdentity(template);
        Set<String> checkTokens = diagnosticTokens(
            firstText(check.checkId(), "") + " " + firstText(check.capability(), "")
                + " " + firstText(check.dimension(), "") + " " + firstText(callContext, ""));
        Set<String> canonicalTokens = diagnosticTokens(canonicalId);
        Set<String> identityTokens = diagnosticTokens(fullIdentity);
        int canonicalMatches = 0;
        int totalMatches = 0;
        int score = 0;
        for (String token : checkTokens) {
            if (canonicalTokens.contains(token)) {
                canonicalMatches++;
                totalMatches++;
                score += token.length() * 20;
            } else if (identityTokens.contains(token)) {
                totalMatches++;
                score += token.length();
            }
        }
        String normalizedIdentity = normalizeDiagnosticPhrase(fullIdentity);
        boolean exactSemanticPhrase = java.util.Arrays.asList(
                check.checkId(), check.capability(), check.dimension())
            .stream()
            .map(this::normalizeDiagnosticPhrase)
            .filter(phrase -> phrase.length() >= 2)
            .anyMatch(normalizedIdentity::contains);
        if (exactSemanticPhrase) {
            score += 100;
        }
        boolean strongMatch = canonicalMatches > 0 || totalMatches >= 2 || exactSemanticPhrase;
        if (strongMatch) {
            return score;
        }
        // A check whose id and declared capability are the same represents a broad
        // capability request. Discovery may legitimately return a more specific child
        // template whose description contributes only one shared term. In contrast, a
        // check with a refined capability must retain that qualifier and cannot fall back
        // to a single descriptive token.
        String normalizedCheckId = normalizeDiagnosticPhrase(check.checkId());
        boolean broadCapability = !normalizedCheckId.isBlank()
            && normalizedCheckId.equals(normalizeDiagnosticPhrase(check.capability()));
        return broadCapability && totalMatches == 1 ? score : 0;
    }

    private String normalizeDiagnosticPhrase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
            .trim();
    }

    private int diagnosticSemanticScore(InterpretationPlan.DiagnosticCheck check,
                                        String templateIdentity,
                                        boolean requireStrongMatch) {
        if (check == null || templateIdentity == null || templateIdentity.isBlank()) {
            return 0;
        }
        String normalizedIdentity = templateIdentity.toLowerCase(Locale.ROOT);
        Set<String> templateTokens = diagnosticTokens(templateIdentity);
        Set<String> checkTokens = diagnosticTokens(
            firstText(check.checkId(), "") + " " + firstText(check.capability(), "")
                + " " + firstText(check.dimension(), "")
        );
        int score = 0;
        int matchedTokenCount = 0;
        for (String token : checkTokens) {
            if (templateTokens.contains(token) || normalizedIdentity.contains(token)) {
                score += token.length();
                matchedTokenCount++;
            }
        }
        boolean exactSemanticPhrase = false;
        for (String phrase : java.util.Arrays.asList(
            firstText(check.capability(), ""),
            firstText(check.dimension(), "")
        )) {
            if (phrase == null) {
                continue;
            }
            String normalizedPhrase = phrase.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
            if (normalizedPhrase.length() >= 2 && normalizedIdentity.contains(normalizedPhrase)) {
                score += normalizedPhrase.length() * 4;
                exactSemanticPhrase = true;
            }
        }
        // One generic shared word (for example "process" in an IO template) is not
        // enough evidence to bind a required diagnostic check to that template.
        return !requireStrongMatch || exactSemanticPhrase || matchedTokenCount >= 2 ? score : 0;
    }

    private Set<String> diagnosticTokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> ignored = Set.of(
            "database", "query", "execute", "template", "diagnostic", "check"
        );
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : value.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+")) {
            String token = raw.endsWith("s") && raw.length() > 4
                ? raw.substring(0, raw.length() - 1)
                : raw;
            boolean han = token.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
            if ((han && token.length() >= 2 || !han && token.length() >= 4)
                && !ignored.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> diagnosticBatchArguments(
        Map<String, Object> input,
        Map<String, Object> template,
        String templateId
    ) {
        Map<String, Object> arguments = new LinkedHashMap<>(input == null ? Map.of() : input);
        for (String key : List.of(
            "calls", "toolCalls", "tool_calls", "batchId", "executionMode", "stopOnFailure",
            "templateCode", "template_code", "templateId", "template_id", "template",
            "runtimeTemplateBinding"
        )) {
            arguments.remove(key);
        }
        arguments.put("templateId", templateId);
        arguments.put("template", templateId);
        Object existingContext = firstMapValue(arguments, "executionContext", "mcpExecutionContext");
        Map<String, Object> executionContext = existingContext instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Object templateContext = firstValueAtAnyPath(template,
            "$.sqlExecutionBinding.executionContext",
            "$.executionBinding.executionContext",
            "$.execution.executionContext",
            "$.executionContext");
        if (templateContext instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    executionContext.putIfAbsent(String.valueOf(key), value);
                }
            });
        }
        if (!executionContext.isEmpty()) {
            arguments.put("executionContext", executionContext);
        }
        return arguments;
    }

    private boolean requiredTemplateParametersSatisfied(
        Map<String, Object> template,
        Map<String, Object> arguments
    ) {
        return missingRequiredTemplateParameters(template, arguments).isEmpty();
    }

    private List<String> missingRequiredTemplateParameters(
        Map<String, Object> template,
        Map<String, Object> arguments
    ) {
        List<String> required = requiredTemplateParameters(template);
        Object parametersValue = arguments == null ? null : arguments.get("parameters");
        Map<?, ?> parameters = parametersValue instanceof Map<?, ?> map ? map : Map.of();
        return required.stream().filter(name -> {
            Object value = parameters.get(name);
            return (value == null || String.valueOf(value).isBlank())
                && !templateParameterHasDefault(template, name);
        }).toList();
    }

    private Boolean booleanObject(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(normalized) ? Boolean.TRUE
            : "false".equalsIgnoreCase(normalized) ? Boolean.FALSE
            : null;
    }

    private Map<String, Object> completedTemplateMetadataByToolName(Map<Integer, StepExecution> completed, String toolName) {
        if (completed == null || completed.isEmpty() || toolName == null || toolName.isBlank()) {
            return Map.of();
        }
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isTemplateDiscoveryTool(execution.toolName())) {
                continue;
            }
            Map<String, Object> template = templateMetadataByToolName(execution.output(), toolName);
            if (!template.isEmpty()) {
                return template;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> templateMetadataByToolName(Object output, String toolName) {
        for (Object item : templateCandidates(output)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) map);
            if (templateNameMatches(template, toolName)) {
                return template;
            }
        }
        return Map.of();
    }

    private boolean templateNameMatches(Map<String, Object> template, String toolName) {
        if (template == null || template.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        Object[] candidates = new Object[] {
            firstValueAtAnyPath(template, "$.templateId"),
            firstValueAtAnyPath(template, "$.id"),
            firstValueAtAnyPath(template, "$.code"),
            firstValueAtAnyPath(template, "$.template"),
            firstValueAtAnyPath(template, "$.mcpToolName"),
            firstValueAtAnyPath(template, "$.toolName"),
            firstValueAtAnyPath(template, "$.execution.callTool"),
            firstValueAtAnyPath(template, "$.sqlExecutionBinding.templateId")
        };
        for (Object value : candidates) {
            if (sameToolName(value == null ? null : String.valueOf(value), toolName)) {
                return true;
            }
        }
        return false;
    }

    private String resolveExecutionToolName(String executor, List<String> allowedTools) {
        if (executor == null || executor.isBlank()) {
            return null;
        }
        if (allowedTools == null || allowedTools.isEmpty()) {
            return executor.trim();
        }
        for (String allowed : allowedTools) {
            if (executorToolNameMatches(allowed, executor)) {
                return allowed;
            }
        }
        return null;
    }

    private boolean sameToolName(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim())
            || toolSemanticKey(left).equals(toolSemanticKey(right));
    }

    @SuppressWarnings("unchecked")
    private void normalizeSqlExecutionContext(InterpretationPlan.Step step, Map<String, Object> input) {
        if (step == null || input == null || !isSqlQueryExecuteTool(step.toolName())) {
            return;
        }
        Object existing = firstMapValue(input, "executionContext", "mcpExecutionContext");
        Map<String, Object> executionContext = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Object parametersValue = input.get("parameters");
        if (parametersValue instanceof Map<?, ?> parametersMap) {
            Map<String, Object> parameters = new LinkedHashMap<>((Map<String, Object>) parametersMap);
            removeRoutingSchemaMistakes(parameters, executionContext);
            input.put("parameters", parameters);
        }
        if (!executionContext.isEmpty()) {
            input.put("executionContext", executionContext);
        }
    }

    private void removeRoutingSchemaMistakes(Map<String, Object> parameters, Map<String, Object> executionContext) {
        if (parameters == null || parameters.isEmpty() || executionContext == null || executionContext.isEmpty()) {
            return;
        }
        Object assetName = firstNonBlankObject(
            executionContext.get("assetName"),
            executionContext.get("asset_name"),
            executionContext.get("name")
        );
        if (assetName == null || String.valueOf(assetName).isBlank()) {
            return;
        }
        for (String key : List.of("schemaName", "schema_name", "schema", "databaseName", "database_name", "database")) {
            Object value = parameters.get(key);
            if (value != null && String.valueOf(assetName).equals(String.valueOf(value))) {
                parameters.remove(key);
            }
        }
    }

    private Object firstNonBlankObject(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void repairTableScopedSqlTemplate(InterpretationPlan.Step step,
                                              Map<Integer, StepExecution> completed,
                                              Map<String, Object> input) {
        if (step == null || input == null || !isSqlQueryExecuteTool(step.toolName())) {
            return;
        }
        Object templateIdValue = firstValueAtAnyPath(input,
            "$.templateId",
            "$.template",
            "$.template_id");
        if (templateIdValue == null || String.valueOf(templateIdValue).isBlank()) {
            return;
        }
        String templateId = String.valueOf(templateIdValue).trim();
        Map<String, Object> selectedTemplate = completedTemplateMetadata(completed, templateId);
        Object tableName = firstValueAtAnyPath(input,
            "$.parameters.table_name",
            "$.parameters.tableName",
            "$.table_name",
            "$.tableName",
            "$.executionContext.table_name",
            "$.executionContext.tableName");
        if (tableName == null || String.valueOf(tableName).isBlank()) {
            return;
        }
        if (isTableScopedSqlTemplate(templateId, selectedTemplate)) {
            return;
        }
        String repairedTemplateId = tableMetadataTemplateId(templateId, input, completed, selectedTemplate);
        if (repairedTemplateId == null) {
            throw new IllegalStateException("SQL_TEMPLATE_TARGET_SCOPE_MISMATCH: template " + templateId
                + " is not table-scoped but tableName=" + tableName
                + " was provided; planner must select a dialect-specific *_TABLE_METADATA template.");
        }
        Object existing = input.get("parameters");
        Map<String, Object> parameters = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        parameters.putIfAbsent("table_name", tableName);
        parameters.putIfAbsent("tableName", tableName);
        input.put("parameters", parameters);
        input.put("templateId", repairedTemplateId);
        input.put("template", repairedTemplateId);
        input.put("runtimeTemplateRepair", Map.of(
            "schemaVersion", "sql_template_repair.v1",
            "fromTemplateId", templateId,
            "toTemplateId", repairedTemplateId,
            "reason", "A tableName was provided, but the selected template is database/instance scoped and cannot satisfy table-scoped metadata analysis.",
            "tableName", String.valueOf(tableName)
        ));
    }

    private boolean isTableScopedSqlTemplate(String templateId, Map<String, Object> templateMetadata) {
        Object targetLevel = firstValueAtAnyPath(templateMetadata,
            "$.semantic.targetLevel",
            "$.targetLevel",
            "$.target_level");
        if (targetLevel != null && "table".equalsIgnoreCase(String.valueOf(targetLevel).trim())) {
            return true;
        }
        if (requiresTableName(templateMetadata)) {
            Object operation = firstValueAtAnyPath(templateMetadata, "$.semantic.operation", "$.operation");
            Object category = firstValueAtAnyPath(templateMetadata, "$.category");
            if (containsText(operation, "metadata") || containsText(category, "metadata")
                || containsText(firstValueAtAnyPath(templateMetadata, "$.intentSignals"), "metadata")) {
                return true;
            }
        }
        if (templateId == null || templateId.isBlank()) {
            return false;
        }
        String normalized = templateId.trim().toUpperCase(Locale.ROOT);
        return normalized.endsWith("_TABLE_METADATA") || normalized.endsWith("_TABLE_LOCATION");
    }

    private String tableMetadataTemplateId(String templateId,
                                           Map<String, Object> input,
                                           Map<Integer, StepExecution> completed,
                                           Map<String, Object> selectedTemplate) {
        String dialect = inferSqlDialect(templateId, input, selectedTemplate);
        if (dialect == null) {
            return null;
        }
        String discovered = tableMetadataTemplateFromCompleted(completed, dialect);
        if (discovered != null) {
            return discovered;
        }
        return null;
    }

    private String inferSqlDialect(String templateId, Map<String, Object> input, Map<String, Object> selectedTemplate) {
        Object configured = firstValueAtAnyPath(input,
            "$.databaseType",
            "$.dbType",
            "$.dialect",
            "$.executionContext.databaseType",
            "$.executionContext.dbType",
            "$.executionContext.dialect",
            "$.executionContext.datasource.databaseType",
            "$.executionContext.routedTarget.databaseType",
            "$.datasource.databaseType");
        String dialect = configured == null || String.valueOf(configured).isBlank()
            ? null
            : normalizeSqlDialect(String.valueOf(configured));
        if (dialect != null) {
            return dialect;
        }
        Object templateDialect = firstValueAtAnyPath(selectedTemplate,
            "$.semantic.dialect",
            "$.semantic.dialects[0]",
            "$.databaseType",
            "$.dialect");
        dialect = templateDialect == null || String.valueOf(templateDialect).isBlank()
            ? null
            : normalizeSqlDialect(String.valueOf(templateDialect));
        return dialect != null ? dialect : dialectFromTemplateId(templateId);
    }

    private String tableMetadataTemplateFromCompleted(Map<Integer, StepExecution> completed, String dialect) {
        if (completed == null || completed.isEmpty() || dialect == null || dialect.isBlank()) {
            return null;
        }
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isTemplateDiscoveryTool(execution.toolName())) {
                continue;
            }
            Object templates = firstValueAtAnyPath(execution.output(), "$.templates", "$.data.templates");
            if (!(templates instanceof Iterable<?> iterable)) {
                continue;
            }
            for (Object item : iterable) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) map);
                if (!isTableMetadataTemplate(template) || !dialectMatchesTemplate(template, dialect)) {
                    continue;
                }
                Object id = firstValueAtAnyPath(template, "$.templateId", "$.id", "$.code", "$.template");
                if (id != null && !String.valueOf(id).isBlank()) {
                    return String.valueOf(id).trim();
                }
            }
        }
        return null;
    }

    private boolean isTableMetadataTemplate(Map<String, Object> template) {
        Object operation = firstValueAtAnyPath(template, "$.semantic.operation", "$.operation");
        Object targetLevel = firstValueAtAnyPath(template, "$.semantic.targetLevel", "$.targetLevel", "$.target_level");
        if ("table".equalsIgnoreCase(String.valueOf(targetLevel))
            && containsText(operation, "metadata")) {
            return true;
        }
        return isTableScopedSqlTemplate(String.valueOf(firstValueAtAnyPath(template, "$.templateId", "$.id", "$.code")), template)
            && containsText(firstValueAtAnyPath(template, "$.category", "$.intentSignals"), "metadata");
    }

    private boolean dialectMatchesTemplate(Map<String, Object> template, String dialect) {
        Object value = firstValueAtAnyPath(template,
            "$.semantic.dialect",
            "$.semantic.dialects[0]",
            "$.databaseType",
            "$.dialect");
        String templateDialect = value == null ? null : normalizeSqlDialect(String.valueOf(value));
        return templateDialect != null && templateDialect.equals(normalizeSqlDialect(dialect));
    }

    private boolean requiresTableName(Map<String, Object> templateMetadata) {
        Object required = firstValueAtAnyPath(templateMetadata,
            "$.parameterSchema.required",
            "$.inputSchema.required",
            "$.schema.required");
        if (!(required instanceof Iterable<?> iterable)) {
            return false;
        }
        for (Object item : iterable) {
            if (item != null && "tablename".equals(String.valueOf(item).replace("_", "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsText(Object value, String needle) {
        if (value == null || needle == null || needle.isBlank()) {
            return false;
        }
        String loweredNeedle = needle.toLowerCase(Locale.ROOT);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsText(item, needle)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (containsText(item, needle)) {
                    return true;
                }
            }
            return false;
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT).contains(loweredNeedle);
    }

    private String dialectFromTemplateId(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        String normalized = templateId.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("MYSQL_")) {
            return "mysql";
        }
        if (normalized.startsWith("ORACLE_")) {
            return "oracle";
        }
        if (normalized.startsWith("POSTGRES_") || normalized.startsWith("POSTGRESQL_")) {
            return "postgresql";
        }
        if (normalized.startsWith("SQLSERVER_") || normalized.startsWith("SQL_SERVER_") || normalized.startsWith("MSSQL_")) {
            return "sqlserver";
        }
        return null;
    }

    private String normalizeSqlDialect(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.contains("mysql")) {
            return "mysql";
        }
        if (normalized.contains("oracle")) {
            return "oracle";
        }
        if (normalized.contains("postgres")) {
            return "postgresql";
        }
        if (normalized.contains("sqlserver") || normalized.contains("sql_server") || normalized.contains("mssql")) {
            return "sqlserver";
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private String canonicalTemplateId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object nested = firstValueAtAnyPath(new LinkedHashMap<>((Map<String, Object>) map),
                "$.templateId", "$.template_id", "$.id", "$.code", "$.template",
                "$.execution.templateId", "$.execution.template",
                "$.executionBinding.templateId", "$.sqlExecutionBinding.templateId");
            return nested == value ? null : canonicalTemplateId(nested);
        }
        if (value instanceof Iterable<?> || value.getClass().isArray()) {
            return null;
        }
        if (!(value instanceof CharSequence)) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                return canonicalTemplateId(RESULT_OBJECT_MAPPER.readValue(text, Object.class));
            } catch (Exception ignored) {
                return null;
            }
        }
        return text;
    }

    private void validateTemplateExecutionArgumentContract(InterpretationPlan.Step step,
                                                           Map<String, Object> input) {
        if (step == null || input == null || !isTemplateExecutionTool(step.toolName())) {
            return;
        }
        Object templateId = input.get("templateId");
        Object template = input.get("template");
        if (!(templateId instanceof String id) || id.isBlank()
            || !(template instanceof String alias) || alias.isBlank() || !id.equals(alias)) {
            throw new IllegalStateException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: templateId and template must be "
                + "the same non-empty scalar string");
        }
        Object parameters = input.get("parameters");
        if (parameters != null && !(parameters instanceof Map<?, ?>)) {
            throw new IllegalStateException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: parameters must be an object "
                + "containing execution values only");
        }
        if (parameters instanceof Map<?, ?> map
            && isJsonSchemaObject(new LinkedHashMap<>((Map<String, Object>) map))) {
            throw new IllegalStateException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: parameterSchema is read-only "
                + "metadata and cannot be passed as parameters");
        }
        for (String contextKey : List.of("executionContext", "mcpExecutionContext")) {
            Object context = input.get(contextKey);
            if (context != null && !(context instanceof Map<?, ?>)) {
                throw new IllegalStateException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: " + contextKey
                    + " must be an object");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String uniqueCompletedTemplateForExecutor(String toolName, Map<Integer, StepExecution> completed) {
        if (toolName == null || toolName.isBlank() || completed == null || completed.isEmpty()) {
            return null;
        }
        Set<String> templateIds = new LinkedHashSet<>();
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isTemplateDiscoveryTool(execution.toolName())) {
                continue;
            }
            for (Object item : templateCandidates(execution.output())) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) map);
                if (!templateExecutorMatches(template, toolName)) {
                    continue;
                }
                String templateId = stringValue(firstValueAtAnyPath(template,
                    "$.templateId",
                    "$.id",
                    "$.code",
                    "$.template"));
                if (templateId != null && !templateId.isBlank()) {
                    templateIds.add(templateId.trim());
                }
            }
        }
        return templateIds.size() == 1 ? templateIds.iterator().next() : null;
    }

    private boolean templateExecutorMatches(Map<String, Object> template, String toolName) {
        if (template == null || template.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        Object[] candidates = new Object[] {
            firstValueAtAnyPath(template, "$.parameterContract.executionTool"),
            firstValueAtAnyPath(template, "$.invocationExample.tool"),
            firstValueAtAnyPath(template, "$.sqlExecutionBinding.toolName"),
            firstValueAtAnyPath(template, "$.executionBinding.toolName"),
            firstValueAtAnyPath(template, "$.execution.executorTool"),
            firstValueAtAnyPath(template, "$.execution.toolName"),
            firstValueAtAnyPath(template, "$.execution.executionTool"),
            firstValueAtAnyPath(template, "$.executionTool")
        };
        for (Object value : candidates) {
            if (executorToolNameMatches(value == null ? null : String.valueOf(value), toolName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Template metadata stores a stable logical executor while an MCP transport may prepend a
     * deployment-specific namespace. Matching is limited to an underscore-delimited suffix and
     * the selected name must still be present in the request's allowed-tools list.
     */
    private boolean executorToolNameMatches(String left, String right) {
        if (sameToolName(left, right)) {
            return true;
        }
        String leftKey = toolSemanticKey(left);
        String rightKey = toolSemanticKey(right);
        return !leftKey.isBlank() && !rightKey.isBlank()
            && (leftKey.endsWith("_" + rightKey) || rightKey.endsWith("_" + leftKey));
    }

    private boolean isJsonSchemaObject(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        Object type = values.get("type");
        return "object".equalsIgnoreCase(type == null ? "" : String.valueOf(type))
            && values.get("properties") instanceof Map<?, ?>
            && (values.get("required") == null || values.get("required") instanceof Iterable<?>);
    }

    @SuppressWarnings("unchecked")
    private void hydrateSqlMetadataParametersFromMetadataSearch(InterpretationPlan.Step step,
                                                                Map<Integer, StepExecution> completed,
                                                                Map<String, Object> input) {
        if (step == null || input == null || completed == null || completed.isEmpty()
            || !isSqlQueryExecuteTool(step.toolName())) {
            return;
        }
        Object templateIdValue = firstValueAtAnyPath(input, "$.templateId", "$.template", "$.template_id");
        if (!isTableScopedSqlTemplate(templateIdValue == null ? null : String.valueOf(templateIdValue),
            completedTemplateMetadata(completed, templateIdValue == null ? null : String.valueOf(templateIdValue)))) {
            return;
        }
        Object existing = input.get("parameters");
        Map<String, Object> parameters = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Object tableName = firstNonBlankObject(
            parameters.get("tableName"),
            parameters.get("table_name"),
            parameters.get("table")
        );
        if (tableName == null || String.valueOf(tableName).isBlank()) {
            return;
        }
        Map<String, Object> resolved = resolvedTableFromMetadataSearch(completed, String.valueOf(tableName));
        if (resolved.isEmpty()) {
            return;
        }
        Object database = firstNonBlankObject(resolved.get("database"), resolved.get("schema"));
        Object table = firstNonBlankObject(resolved.get("table"), tableName);
        if (database == null || String.valueOf(database).isBlank()) {
            return;
        }
        parameters.put("schemaName", String.valueOf(database));
        parameters.put("databaseName", String.valueOf(database));
        parameters.put("schema", String.valueOf(database));
        parameters.put("database", String.valueOf(database));
        parameters.put("tableName", String.valueOf(table));
        parameters.putIfAbsent("table_name", String.valueOf(table));
        input.put("parameters", parameters);
        input.put("runtimeTableResolution", Map.of(
            "schemaVersion", "runtime_table_resolution.v1",
            "source", "sql_metadata_search.results",
            "database", String.valueOf(database),
            "schema", String.valueOf(firstNonBlankObject(resolved.get("schema"), database)),
            "table", String.valueOf(table),
            "score", resolved.get("score")
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvedTableFromMetadataSearch(Map<Integer, StepExecution> completed, String requestedTable) {
        if (completed == null || completed.isEmpty() || requestedTable == null || requestedTable.isBlank()) {
            return Map.of();
        }
        String requested = canonicalParameterKey(requestedTable);
        Map<String, Object> best = Map.of();
        double bestScore = -1.0;
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isSqlMetadataSearchTool(execution.toolName())) {
                continue;
            }
            Object resolvedTables = firstValueAtAnyPath(
                execution.output(),
                "$.results",
                "$.data.results",
                "$.structuredContent.results",
                "$.data.structuredContent.results"
            );
            if (!(resolvedTables instanceof Iterable<?> iterable)) {
                continue;
            }
            for (Object item : iterable) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> candidate = metadataSearchCandidate((Map<String, Object>) map);
                Object table = candidate.get("table");
                if (table == null || !requested.equals(canonicalParameterKey(String.valueOf(table)))) {
                    continue;
                }
                double score = doubleValue(candidate.get("score"), 0.0);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataSearchCandidate(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        Object location = result.get("location");
        if (location instanceof Map<?, ?> locationMap) {
            candidate.putAll((Map<String, Object>) locationMap);
        }
        Object bindingParameters = firstValueAtAnyPath(result,
            "$.sqlExecutionBinding.parameters",
            "$.binding.parameters");
        if (bindingParameters instanceof Map<?, ?> parametersMap) {
            Map<String, Object> parameters = (Map<String, Object>) parametersMap;
            putIfAbsent(candidate, "database", parameters.get("databaseName"));
            putIfAbsent(candidate, "schema", parameters.get("schemaName"));
            putIfAbsent(candidate, "table", parameters.get("tableName"));
            putIfAbsent(candidate, "tableName", parameters.get("tableName"));
        }
        putIfAbsent(candidate, "score", result.get("score"));
        return candidate;
    }

    private void putIfAbsent(Map<String, Object> values, String key, Object value) {
        if (values == null || key == null || values.containsKey(key) || value == null || String.valueOf(value).isBlank()) {
            return;
        }
        values.put(key, value);
    }

    private boolean requiresTemplateParameterProtocol(ExecutionRequest request) {
        return request != null && request.attributes() != null
            && Boolean.TRUE.equals(request.attributes().get("requireTemplateParameterProtocol"));
    }

    private void validateRequiredExecutionTemplate(InterpretationPlan.Step step,
                                                   Map<String, Object> input,
                                                   Map<Integer, StepExecution> completed) {
        if (step == null || input == null
            || (!requiresTemplateId(step.toolName())
                && !sqlExecutionDependsOnTemplateDiscovery(step, completed))) {
            return;
        }
        Object templateId = firstValueAtAnyPath(input,
            "$.template",
            "$.templateId",
            "$.template_id");
        if (templateId != null && !String.valueOf(templateId).isBlank()) {
            return;
        }
        throw new IllegalStateException("TEMPLATE_REQUIRED: " + step.toolName()
            + " must be called with template/templateId returned by the matching template_query step. "
            + "Do not retry template execution with an empty template.");
    }

    private boolean sqlExecutionDependsOnTemplateDiscovery(InterpretationPlan.Step step,
                                                           Map<Integer, StepExecution> completed) {
        if (step == null || !isSqlQueryExecuteTool(step.toolName())
            || completed == null || completed.isEmpty()) {
            return false;
        }
        return safeIntegerList(step.dependsOn()).stream()
            .map(completed::get)
            .filter(java.util.Objects::nonNull)
            .anyMatch(execution -> isTemplateDiscoveryTool(execution.toolName()));
    }

    private Map<String, Object> completedTemplateMetadata(Map<Integer, StepExecution> completed, String templateId) {
        if (completed == null || completed.isEmpty() || templateId == null || templateId.isBlank()) {
            return Map.of();
        }
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isTemplateDiscoveryTool(execution.toolName())) {
                continue;
            }
            Map<String, Object> template = templateMetadataById(execution.output(), templateId);
            if (!template.isEmpty()) {
                return template;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> templateMetadataById(Object output, String templateId) {
        for (Object item : templateCandidates(output)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) map);
            Object id = firstValueAtAnyPath(template, "$.templateId", "$.id", "$.code", "$.template");
            if (id != null && templateId.equalsIgnoreCase(String.valueOf(id))) {
                return template;
            }
        }
        return Map.of();
    }

    private List<Object> templateCandidates(Object output) {
        List<Object> values = new ArrayList<>();
        collectTemplateCandidates(output, values, 0);
        return values;
    }

    @SuppressWarnings("unchecked")
    private void collectTemplateCandidates(Object output, List<Object> values, int depth) {
        if (output == null || values == null || depth > 8) return;
        if (output instanceof CharSequence text) {
            String json = text.toString().trim();
            if (json.startsWith("{") || json.startsWith("[")) {
                try {
                    collectTemplateCandidates(RESULT_OBJECT_MAPPER.readValue(json, Object.class), values, depth + 1);
                } catch (Exception ignored) {
                    // A text result is not an executable template contract.
                }
            }
            return;
        }
        if (!(output instanceof Map<?, ?> raw)) return;
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        addIterable(values, firstMapValue(map, "templates", "associatedTemplates", "associated_templates"));
        Object results = firstMapValue(map, "results", "items");
        if (results instanceof Iterable<?> iterable) {
            for (Object result : iterable) {
                addIterable(values, firstValueAtAnyPath(result,
                    "$.associatedTemplates",
                    "$.templates",
                    "$.data.associatedTemplates",
                    "$.data.templates"));
            }
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection"
        )) {
            collectTemplateCandidates(map.get(key), values, depth + 1);
        }
    }

    private void addIterable(List<Object> target, Object value) {
        if (target == null || !(value instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object item : iterable) {
            target.add(item);
        }
    }

    private List<String> requiredTemplateParameters(Map<String, Object> template) {
        Object required = firstValueAtAnyPath(template,
            "$.parameterSchema.required",
            "$.inputSchema.required",
            "$.schema.required");
        if (!(required instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private boolean templateParameterHasDefault(Map<String, Object> template, String parameterName) {
        if (template == null || template.isEmpty() || parameterName == null || parameterName.isBlank()) {
            return false;
        }
        Object schemaValue = firstMapValue(template, "parameterSchema", "parameter_schema", "inputSchema", "schema");
        if (!(schemaValue instanceof Map<?, ?> schema)) {
            return false;
        }
        Object propertiesValue = firstMapValue(schema, "properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return false;
        }
        Object propertyValue = properties.get(parameterName);
        if (!(propertyValue instanceof Map<?, ?> property)) {
            return false;
        }
        Object defaultValue = firstMapValue(property, "default", "defaultValue", "default_value");
        return defaultValue != null && (!(defaultValue instanceof String text) || !text.isBlank());
    }

    private String canonicalParameterKey(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private void hydrateExecutionContextFromCompletedAssets(InterpretationPlan.Step step,
                                                            Map<Integer, StepExecution> completed,
                                                            Map<String, Object> input) {
        if (step == null || input == null || completed == null || completed.isEmpty()
            || !isExecutionContextTool(step.toolName()) || isRoutingDiscoveryTool(step.toolName())) {
            return;
        }
        Object existing = firstMapValue(input, "executionContext", "mcpExecutionContext");
        Map<String, Object> context = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Map<String, Object> assetContext = firstCompletedAssetExecutionContext(completed);
        if (assetContext.isEmpty()) {
            return;
        }
        assetContext.forEach((key, value) -> putIfAbsentOrPlaceholder(context, key, value));
        input.put("executionContext", context);
    }

    @SuppressWarnings("unchecked")
    private void enforceCanonicalAssetContinuity(InterpretationPlan.Step step,
                                                  Map<Integer, StepExecution> completed,
                                                  Map<String, Object> input) {
        if (step == null || input == null
            || (!isTemplateDiscoveryTool(step.toolName()) && !isExecutionContextTool(step.toolName()))) {
            return;
        }
        Map<String, Object> canonical = uniqueCompletedAssetExecutionContext(completed);
        if (canonical.isEmpty()) {
            return;
        }
        String envelopeKey = isTemplateDiscoveryTool(step.toolName()) ? "filters" : "executionContext";
        Object rawTarget = firstMapValue(input, envelopeKey,
            isTemplateDiscoveryTool(step.toolName()) ? "executionContext" : "mcpExecutionContext");
        Map<String, Object> target = rawTarget instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        assertSameCanonicalAsset("assetId", canonical.get("assetId"),
            firstNonBlankObject(target.get("assetId"), target.get("asset_id")), step);
        reconcileCanonicalAssetName(canonical, target, completed, step);
        assertSameCanonicalEnvironment(canonical.get("env"),
            firstNonBlankObject(target.get("env"), target.get("environment")), step);
        if (isTemplateDiscoveryTool(step.toolName())) {
            putIfAbsentOrPlaceholder(target, "assetName", canonical.get("assetName"));
            putIfAbsentOrPlaceholder(target, "env", canonical.get("env"));
        } else {
            canonical.forEach((key, value) -> putIfAbsentOrPlaceholder(target, key, value));
        }
        input.put(envelopeKey, target);
    }

    private void reconcileCanonicalAssetName(Map<String, Object> canonical,
                                             Map<String, Object> target,
                                             Map<Integer, StepExecution> completed,
                                             InterpretationPlan.Step step) {
        Object canonicalValue = canonical == null ? null : canonical.get("assetName");
        Object suppliedValue = firstNonBlankObject(
            target.get("assetName"), target.get("asset_name"), target.get("name"));
        if (canonicalValue == null || suppliedValue == null) {
            return;
        }
        if (isPlannerPlaceholder(suppliedValue)) {
            target.put("assetName", canonicalValue);
            target.remove("asset_name");
            target.remove("name");
            return;
        }
        String canonicalName = String.valueOf(canonicalValue);
        String suppliedName = String.valueOf(suppliedValue);
        if (!sameAssetIdentityText(canonicalName, suppliedName)) {
            if (!verifiedAssetDiscoveryAlias(canonical, suppliedName, completed)) {
                throw new IllegalStateException("ASSET_CONTEXT_MISMATCH: step " + step.id() + " ("
                    + step.toolName() + ") supplied assetName=" + suppliedValue
                    + " but asset discovery established assetName=" + canonicalValue);
            }
            log.info("InterpretationPlan canonicalized verified asset discovery alias: stepId={}, "
                    + "tool={}, suppliedAssetName={}, canonicalAssetName={}",
                step.id(), step.toolName(), suppliedName, canonicalName);
        }
        target.put("assetName", canonicalName);
        target.remove("asset_name");
        Object alternateName = target.get("name");
        if (alternateName != null && sameAssetIdentityText(String.valueOf(alternateName), suppliedName)) {
            target.remove("name");
        }
    }

    private boolean verifiedAssetDiscoveryAlias(Map<String, Object> canonical,
                                                String suppliedName,
                                                Map<Integer, StepExecution> completed) {
        if (suppliedName == null || suppliedName.isBlank() || completed == null) {
            return false;
        }
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isAssetDiscoveryTool(execution.toolName())
                || discoveredAssetCount(execution.output(), "assets") != 1) {
                continue;
            }
            Map<String, Object> candidate = assetExecutionContext(execution.output());
            if (candidate.isEmpty() || !sameCanonicalAsset(canonical, candidate)) {
                continue;
            }
            for (Object identity : new Object[] {
                candidate.get("assetName"),
                candidate.get("assetDisplayName"),
                candidate.get("assetToolName"),
                candidate.get("assetId"),
                firstValueAtAnyPath(execution.metadata(),
                    "$.resolvedInput.filters.assetName",
                    "$.resolvedInput.filters.asset_name",
                    "$.resolvedInput.filters.name"),
                firstValueAtAnyPath(execution.output(),
                    "$.filters.assetName", "$.filters.asset_name", "$.filters.name")
            }) {
                if (identity != null && sameAssetIdentityText(String.valueOf(identity), suppliedName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sameAssetIdentityText(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private Map<String, Object> uniqueCompletedAssetExecutionContext(Map<Integer, StepExecution> completed) {
        Map<String, Object> reviewed = reviewSelectedAssetExecutionContext(completed);
        if (!reviewed.isEmpty()) {
            return reviewed;
        }
        if (completed == null || completed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> unique = Map.of();
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isAssetDiscoveryTool(execution.toolName())
                || discoveredAssetCount(execution.output(), "assets") != 1) {
                continue;
            }
            Map<String, Object> candidate = assetExecutionContext(execution.output());
            if (candidate.isEmpty()) {
                continue;
            }
            if (!unique.isEmpty() && !sameCanonicalAsset(unique, candidate)) {
                return Map.of();
            }
            unique = candidate;
        }
        return unique;
    }

    private boolean sameCanonicalAsset(Map<String, Object> left, Map<String, Object> right) {
        String leftId = stringValue(left.get("assetId"));
        String rightId = stringValue(right.get("assetId"));
        if (leftId != null && rightId != null) {
            return leftId.equals(rightId);
        }
        return Objects.equals(stringValue(left.get("assetName")), stringValue(right.get("assetName")));
    }

    private void assertSameCanonicalAsset(String field,
                                          Object canonicalValue,
                                          Object suppliedValue,
                                          InterpretationPlan.Step step) {
        if (canonicalValue == null || suppliedValue == null
            || isPlannerPlaceholder(suppliedValue)
            || String.valueOf(canonicalValue).equals(String.valueOf(suppliedValue))) {
            return;
        }
        throw new IllegalStateException("ASSET_CONTEXT_MISMATCH: step " + step.id() + " ("
            + step.toolName() + ") supplied " + field + "=" + suppliedValue
            + " but asset discovery established " + field + "=" + canonicalValue);
    }

    private void assertSameCanonicalEnvironment(Object canonicalValue,
                                                Object suppliedValue,
                                                InterpretationPlan.Step step) {
        if (canonicalValue == null || suppliedValue == null
            || isPlannerPlaceholder(suppliedValue)
            || String.valueOf(canonicalValue).equalsIgnoreCase(String.valueOf(suppliedValue))) {
            return;
        }
        throw new IllegalStateException("ASSET_CONTEXT_MISMATCH: step " + step.id() + " ("
            + step.toolName() + ") supplied env=" + suppliedValue
            + " but asset discovery established env=" + canonicalValue);
    }

    private void putIfAbsentOrPlaceholder(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null || String.valueOf(value).isBlank()) {
            return;
        }
        Object existing = target.get(key);
        if (existing == null || String.valueOf(existing).isBlank() || isPlannerPlaceholder(existing)) {
            target.put(key, value);
        }
    }

    private boolean hasUsableNonBlank(Map<?, ?> input, String... keys) {
        if (input == null || input.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            Object value = input.get(key);
            if (value != null && !String.valueOf(value).isBlank() && !isPlannerPlaceholder(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlannerPlaceholder(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("$.")
            || trimmed.startsWith("$[")
            || trimmed.matches("^<[^<>]+>$")
            || trimmed.matches("^\\$\\{[^{}]+}$")
            || trimmed.matches("^\\{\\{[^{}]+}}$");
    }

    private Map<String, Object> firstCompletedAssetExecutionContext(Map<Integer, StepExecution> completed) {
        if (completed == null || completed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> reviewed = reviewSelectedAssetExecutionContext(completed);
        if (!reviewed.isEmpty()) {
            return reviewed;
        }
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isAssetDiscoveryTool(execution.toolName())) {
                continue;
            }
            Map<String, Object> context = assetExecutionContext(execution.output());
            if (!context.isEmpty()) {
                return context;
            }
        }
        return Map.of();
    }

    private Map<String, Object> reviewSelectedAssetExecutionContext(
        Map<Integer, StepExecution> completed
    ) {
        if (completed == null || completed.isEmpty()) {
            return Map.of();
        }
        for (StepExecution execution : completed.values()) {
            if (execution == null || !execution.success() || !isAssetDiscoveryTool(execution.toolName())
                || execution.metadata() == null) {
                continue;
            }
            Object nextActions = execution.metadata().get("nextActions");
            if (!(nextActions instanceof Iterable<?> actions)) {
                continue;
            }
            for (Object item : actions) {
                if (!(item instanceof Map<?, ?> action)) {
                    continue;
                }
                Object rawChanges = firstPresent(asStringMap(action), "input_changes", "inputChanges");
                Map<String, Object> changes = asStringMap(rawChanges);
                String assetId = stringValue(firstPresent(
                    changes, "assetId", "asset_id", "hostId", "host_id"));
                if (assetId == null) {
                    continue;
                }
                Map<String, Object> verified = assetExecutionContextForId(execution.output(), assetId);
                if (!verified.isEmpty()) {
                    return verified;
                }
                log.warn("InterpretationPlan ignored unverified reviewed asset selection: "
                        + "sourceStepId={}, requestedAssetId={}",
                    execution.stepId(), assetId);
            }
        }
        return Map.of();
    }

    private Map<String, Object> assetExecutionContextForId(Object output, String expectedAssetId) {
        Object assetsValue = firstValueAtAnyPath(routingCapableOutput(output), "$.assets");
        if (!(assetsValue instanceof Iterable<?> assets) || expectedAssetId == null) {
            return Map.of();
        }
        for (Object item : assets) {
            String assetId = stringValue(firstValueAtAnyPath(item,
                "$.asset.id", "$.asset.assetId", "$.assetId", "$.id"));
            if (!expectedAssetId.equals(assetId)) {
                continue;
            }
            Map<String, Object> context = new LinkedHashMap<>();
            putIfPresent(context, "assetId", assetId);
            putIfPresent(context, "assetName", firstValueAtAnyPath(item,
                "$.asset.name", "$.asset.displayName", "$.name", "$.displayName"));
            putIfPresent(context, "assetDisplayName", firstValueAtAnyPath(item,
                "$.asset.displayName", "$.displayName", "$.asset.name", "$.name"));
            putIfPresent(context, "assetToolName", firstValueAtAnyPath(item,
                "$.asset.toolName", "$.toolName"));
            putIfPresent(context, "env", firstValueAtAnyPath(item,
                "$.asset.environment", "$.asset.env", "$.environment", "$.env"));
            putIfPresent(context, "databaseRole", firstValueAtAnyPath(item,
                "$.asset.databaseRole", "$.asset.database_role", "$.databaseRole"));
            return Map.copyOf(context);
        }
        return Map.of();
    }

    private Map<String, Object> assetExecutionContext(Object output) {
        output = routingCapableOutput(output);
        Map<String, Object> context = new LinkedHashMap<>();
        Object assetName = firstValueAtAnyPath(output,
            "$.assets[0].asset.name",
            "$.assets[0].asset.displayName",
            "$.assets[0].name",
            "$.asset.name",
            "$.name");
        Object env = firstValueAtAnyPath(output,
            "$.assets[0].asset.environment",
            "$.assets[0].asset.env",
            "$.assets[0].environment",
            "$.asset.environment",
            "$.environment",
            "$.env");
        Object databaseRole = firstValueAtAnyPath(output,
            "$.assets[0].asset.databaseRole",
            "$.assets[0].asset.database_role",
            "$.assets[0].databaseRole",
            "$.asset.databaseRole",
            "$.databaseRole");
        Object assetId = firstValueAtAnyPath(output,
            "$.assets[0].asset.id",
            "$.assets[0].asset.assetId",
            "$.assets[0].assetId",
            "$.asset.id",
            "$.asset.assetId");
        Object displayName = firstValueAtAnyPath(output,
            "$.assets[0].asset.displayName",
            "$.assets[0].displayName",
            "$.asset.displayName");
        Object assetToolName = firstValueAtAnyPath(output,
            "$.assets[0].asset.toolName",
            "$.assets[0].toolName",
            "$.asset.toolName");
        if (assetName != null && !String.valueOf(assetName).isBlank()) {
            context.put("assetName", String.valueOf(assetName));
        }
        if (env != null && !String.valueOf(env).isBlank()) {
            context.put("env", String.valueOf(env));
        }
        if (databaseRole != null && !String.valueOf(databaseRole).isBlank()) {
            context.put("databaseRole", String.valueOf(databaseRole));
        }
        if (assetId != null && !String.valueOf(assetId).isBlank()) {
            context.put("assetId", String.valueOf(assetId));
        }
        if (displayName != null && !String.valueOf(displayName).isBlank()) {
            context.put("assetDisplayName", String.valueOf(displayName));
        }
        if (assetToolName != null && !String.valueOf(assetToolName).isBlank()) {
            context.put("assetToolName", String.valueOf(assetToolName));
        }
        return context;
    }

    @SuppressWarnings("unchecked")
    private void normalizeDiscoveryRoutingInput(InterpretationPlan.Step step,
                                                ExecutionRequest request,
                                                Map<Integer, StepExecution> completed,
                                                Map<String, Object> input) {
        if (step == null || input == null || !isRoutingDiscoveryTool(step.toolName())) {
            return;
        }
        Object filters = firstMapValue(input, "filters", "executionContext", "mcpExecutionContext");
        if (!(filters instanceof Map<?, ?>)) {
            input.put("filters", new LinkedHashMap<>());
            filters = input.get("filters");
        }
        promoteLooseDiscoveryFilters(input);
        applyReviewedAssetSelectionToTemplateDiscovery(step, completed, input);
        sanitizeDiscoveryFilters(step, request, input);
        sanitizeDiscoveryEnvironment(step, request, completed, input);
        filters = firstMapValue(input, "filters", "executionContext", "mcpExecutionContext");
        if (filters instanceof Map<?, ?> filterMap && !hasTargetIdentityConstraint(filterMap)) {
            String searchText = discoverySearchText(request);
            if (searchText != null && !searchText.isBlank() && !hasRetrievalSignal(filterMap)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutableFilters = filterMap instanceof LinkedHashMap<?, ?>
                    ? (Map<String, Object>) filterMap
                    : new LinkedHashMap<>((Map<String, Object>) filterMap);
                mutableFilters.putIfAbsent("intent", searchText);
                mutableFilters.putIfAbsent("goal", searchText);
                mutableFilters.putIfAbsent("queryTerms", List.of(searchText));
                input.put("filters", mutableFilters);
            }
        }
        sanitizeDiscoveryFilters(step, request, input);
        input.putIfAbsent("filtersSchemaVersion", AgentProtocolCatalog.TARGET_FILTERS);
        Object trace = firstMapValue(input, "trace", "routingTrace", "routing_trace");
        if (trace instanceof Map<?, ?> traceMap && !traceMap.isEmpty()) {
            if (!input.containsKey("trace")) {
                input.put("trace", new LinkedHashMap<>((Map<String, Object>) traceMap));
            }
            return;
        }
        input.put("trace", routingTraceForStep(step, request));
    }

    @SuppressWarnings("unchecked")
    private void promoteLooseDiscoveryFilters(Map<String, Object> input) {
        Object value = firstMapValue(input, "filters", "executionContext", "mcpExecutionContext");
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> filters = new LinkedHashMap<>((Map<String, Object>) map);
        for (String field : List.of(
            "intent", "goal", "category", "keywords", "queryTerms", "retrievalSignals",
            "intentCandidates", "bilingualIntent", "bilingualQuery", "intentZh", "intentEn"
        )) {
            Object semanticValue = input.remove(field);
            if (semanticValue != null) {
                filters.putIfAbsent(field, semanticValue);
            }
        }
        input.remove("executionContext");
        input.remove("mcpExecutionContext");
        input.put("filters", filters);
    }

    @SuppressWarnings("unchecked")
    private void applyReviewedAssetSelectionToTemplateDiscovery(InterpretationPlan.Step step,
                                                                 Map<Integer, StepExecution> completed,
                                                                 Map<String, Object> input) {
        if (step == null || input == null || !isTemplateDiscoveryTool(step.toolName())) {
            return;
        }
        Map<String, Object> selected = reviewSelectedAssetExecutionContext(completed);
        if (selected.isEmpty()) {
            return;
        }
        Object filtersValue = firstMapValue(input, "filters", "executionContext", "mcpExecutionContext");
        Map<String, Object> filters = filtersValue instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        String previousAsset = stringValue(firstNonBlankObject(
            filters.get("assetName"), filters.get("asset_name"), filters.get("name")));
        String selectedAsset = stringValue(selected.get("assetName"));
        filters.remove("asset_name");
        filters.remove("name");
        if (selectedAsset != null) {
            filters.put("assetName", selectedAsset);
        }
        if (selected.get("env") != null) {
            filters.remove("environment");
            filters.put("env", selected.get("env"));
        }
        input.put("filters", filters);
        if (!Objects.equals(previousAsset, selectedAsset)) {
            log.info("InterpretationPlan corrected template-discovery asset drift from reviewed "
                    + "asset evidence: stepId={}, tool={}, plannedAsset={}, selectedAsset={}, assetId={}",
                step.id(), step.toolName(), previousAsset, selectedAsset, selected.get("assetId"));
        }
    }

    @SuppressWarnings("unchecked")
    private void sanitizeDiscoveryEnvironment(InterpretationPlan.Step step,
                                              ExecutionRequest request,
                                              Map<Integer, StepExecution> completed,
                                              Map<String, Object> input) {
        Object filtersValue = input == null ? null : firstMapValue(input, "filters", "executionContext", "mcpExecutionContext");
        if (!(filtersValue instanceof Map<?, ?> filters)) {
            return;
        }
        Map<String, Object> mutable = filters instanceof LinkedHashMap<?, ?>
            ? (Map<String, Object>) filters
            : new LinkedHashMap<>((Map<String, Object>) filters);
        Object rawValue = firstNonBlankObject(mutable.get("env"), mutable.get("environment"));
        if (rawValue == null) {
            return;
        }
        String rawEnvironment = String.valueOf(rawValue).trim();
        String canonical = canonicalProtocolEnvironment(rawEnvironment);
        String explicit = explicitEnvironment(originalUserQuery(request));
        boolean observed = environmentObserved(completed, canonical);
        boolean requestAttribute = environmentFromAttributes(request, canonical);
        boolean originalQueryAvailable = originalUserQuery(request) != null;
        boolean accepted = canonical != null
            && (observed || requestAttribute || !originalQueryAvailable || canonical.equals(explicit));
        mutable.remove("environment");
        if (accepted) {
            mutable.put("env", canonical);
        } else {
            mutable.remove("env");
            log.info("InterpretationPlan discovery environment filter dropped: stepId={}, tool={}, value={}, reason={}",
                step == null ? null : step.id(), step == null ? null : step.toolName(), rawEnvironment,
                canonical == null ? "not_protocol_enum" : "not_explicit_or_observed");
        }
        input.put("filters", mutable);
    }

    @SuppressWarnings("unchecked")
    private void enforceAgentRuntimeEnvironment(InterpretationPlan.Step step,
                                                ExecutionRequest request,
                                                Map<String, Object> input) {
        String configured = agentRuntimeEnvironment(request);
        if (configured == null || step == null || input == null || !step.mcpToolAction()) {
            return;
        }
        if (isRoutingDiscoveryTool(step.toolName())) {
            Object existing = firstMapValue(input, "filters", "executionContext", "mcpExecutionContext");
            Map<String, Object> filters = existing instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
            logEnvironmentCorrection(step, firstNonBlankObject(filters.get("env"), filters.get("environment")), configured);
            filters.remove("environment");
            filters.put("env", configured);
            input.put("filters", filters);
            return;
        }
        if (!isExecutionContextTool(step.toolName())) {
            return;
        }
        Object existing = firstMapValue(input, "executionContext", "mcpExecutionContext");
        Map<String, Object> context = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        logEnvironmentCorrection(step, firstNonBlankObject(context.get("env"), context.get("environment")), configured);
        context.remove("environment");
        context.put("env", configured);
        input.remove("env");
        input.remove("environment");
        input.remove("mcpExecutionContext");
        input.put("executionContext", context);
    }

    private String agentRuntimeEnvironment(ExecutionRequest request) {
        if (request == null || request.attributes() == null) {
            return null;
        }
        Object value = request.attributes().get(AGENT_RUNTIME_ENVIRONMENT_ATTRIBUTE);
        if (value == null) {
            Object workflow = request.attributes().get("mcpWorkflow");
            if (workflow instanceof Map<?, ?> map) {
                value = map.get("runtimeEnvironment");
            }
        }
        return canonicalProtocolEnvironment(value == null ? null : String.valueOf(value));
    }

    private void logEnvironmentCorrection(InterpretationPlan.Step step, Object actual, String configured) {
        if (actual == null || configured.equals(canonicalProtocolEnvironment(String.valueOf(actual)))) {
            return;
        }
        log.info("InterpretationPlan MCP environment corrected from Agent configuration: stepId={}, tool={}, modelEnv={}, agentEnv={}",
            step.id(), step.toolName(), actual, configured);
    }

    private String discoverySearchText(ExecutionRequest request) {
        String original = originalUserQuery(request);
        return original == null ? planGoalSearchText(request == null ? null : request.plan()) : original;
    }

    private String originalUserQuery(ExecutionRequest request) {
        if (request == null || request.attributes() == null) {
            return null;
        }
        Object value = request.attributes().get(ORIGINAL_USER_QUERY_ATTRIBUTE);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private boolean environmentFromAttributes(ExecutionRequest request, String expected) {
        if (expected == null || request == null || request.attributes() == null) {
            return false;
        }
        if (expected.equals(agentRuntimeEnvironment(request))) {
            return true;
        }
        Object value = firstNonBlankObject(
            request.attributes().get("env"),
            request.attributes().get("environment")
        );
        return expected.equals(canonicalProtocolEnvironment(value == null ? null : String.valueOf(value)));
    }

    private boolean environmentObserved(Map<Integer, StepExecution> completed, String expected) {
        if (expected == null || completed == null || completed.isEmpty()) {
            return false;
        }
        for (StepExecution execution : completed.values()) {
            Object value = firstValueAtAnyPath(execution == null ? null : execution.output(),
                "$.assets[0].asset.environment",
                "$.assets[0].asset.env",
                "$.asset.environment",
                "$.environment",
                "$.env");
            if (expected.equals(canonicalProtocolEnvironment(value == null ? null : String.valueOf(value)))) {
                return true;
            }
        }
        return false;
    }

    private String explicitEnvironment(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (Pattern pattern : List.of(
            EXPLICIT_ENV_ASSIGNMENT_PATTERN,
            EXPLICIT_ENV_QUALIFIER_PATTERN,
            EXPLICIT_ENV_ENGLISH_PATTERN
        )) {
            Matcher matcher = pattern.matcher(query);
            if (matcher.find()) {
                return canonicalEnvironmentToken(matcher.group(1));
            }
        }
        return null;
    }

    private String canonicalProtocolEnvironment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Set.of("DEV", "TEST", "UAT", "PROD").contains(normalized) ? normalized : null;
    }

    private String canonicalEnvironmentToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DEV", "\u5f00\u53d1" -> "DEV";
            case "TEST", "\u6d4b\u8bd5" -> "TEST";
            case "UAT", "\u9884\u53d1" -> "UAT";
            case "PROD", "\u751f\u4ea7" -> "PROD";
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private void sanitizeDiscoveryFilters(InterpretationPlan.Step step,
                                          ExecutionRequest request,
                                          Map<String, Object> input) {
        Object filters = input == null ? null : firstMapValue(input, "filters", "executionContext", "mcpExecutionContext");
        if (!(filters instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> mutableFilters = map instanceof LinkedHashMap<?, ?>
            ? (Map<String, Object>) map
            : new LinkedHashMap<>((Map<String, Object>) map);
        DISCOVERY_FILTER_PROTOCOL_FIELDS.forEach(mutableFilters::remove);
        repairDiscoveryFiltersFromToolMetadata(step, request, mutableFilters);
        if (input.containsKey("filters") || !input.containsKey("executionContext")) {
            input.put("filters", mutableFilters);
        } else if (input.containsKey("executionContext")) {
            input.put("executionContext", mutableFilters);
        } else {
            input.put("mcpExecutionContext", mutableFilters);
        }
    }

    private void repairDiscoveryFiltersFromToolMetadata(InterpretationPlan.Step step,
                                                        ExecutionRequest request,
                                                        Map<String, Object> filters) {
        DiscoveryFilterContract contract = discoveryFilterContract(step, request);
        if (filters == null || filters.isEmpty() || contract.allowedFields().isEmpty()) {
            return;
        }
        List<String> semanticSignals = new ArrayList<>();
        List<String> removedFields = new ArrayList<>();
        List<String> forbiddenFields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : new ArrayList<>(filters.entrySet())) {
            String canonical = canonicalFilterField(entry.getKey());
            if (contract.allowedFields().contains(canonical)) {
                continue;
            }
            filters.remove(entry.getKey());
            if (contract.forbiddenFields().contains(canonical)) {
                forbiddenFields.add(entry.getKey());
                continue;
            }
            removedFields.add(entry.getKey());
            appendSemanticFilterSignals(semanticSignals, entry.getKey(), entry.getValue());
        }
        if (!semanticSignals.isEmpty() && contract.allowedFields().contains("retrievalsignals")) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(stringValues(filters.get("retrievalSignals")));
            merged.addAll(semanticSignals);
            filters.put("retrievalSignals", new ArrayList<>(merged));
        }
        if (!removedFields.isEmpty() || !forbiddenFields.isEmpty()) {
            log.info("InterpretationPlan discovery filters repaired from MCP metadata: stepId={}, tool={}, semanticFields={}, forbiddenFields={}",
                step == null ? null : step.id(), step == null ? null : step.toolName(), removedFields, forbiddenFields);
        }
    }

    private DiscoveryFilterContract discoveryFilterContract(InterpretationPlan.Step step, ExecutionRequest request) {
        if (step == null || request == null || request.toolRegistry() == null) {
            return DiscoveryFilterContract.empty();
        }
        ToolMetadata metadata = request.toolRegistry().getToolMetadata(step.toolName());
        if (metadata == null || metadata.getMetadata() == null) {
            return DiscoveryFilterContract.empty();
        }
        Map<String, Object> metadataMap = asStringMap(metadata.getMetadata());
        Map<String, Object> mcpMeta = asStringMap(metadataMap.get("mcpToolMeta"));
        Map<String, Object> routingProtocol = asStringMap(mcpMeta.get("routingProtocol"));
        Set<String> allowed = stringValues(routingProtocol.get("allowedFilterFields")).stream()
            .map(this::canonicalFilterField)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> forbidden = stringValues(mcpMeta.get("forbiddenConcreteTargetFields")).stream()
            .map(this::canonicalFilterField)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new DiscoveryFilterContract(allowed, forbidden);
    }

    private void appendSemanticFilterSignals(List<String> target, String field, Object value) {
        if (target == null || value == null || value instanceof Map<?, ?>) {
            return;
        }
        for (String item : stringValues(value)) {
            if (item == null || item.isBlank()) {
                continue;
            }
            target.add(field + ":" + item);
            target.add(item);
        }
    }

    private List<String> stringValues(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? List.of() : List.of(text);
    }

    private String canonicalFilterField(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record DiscoveryFilterContract(Set<String> allowedFields, Set<String> forbiddenFields) {
        private static DiscoveryFilterContract empty() {
            return new DiscoveryFilterContract(Set.of(), Set.of());
        }
    }

    private record TemplateExecutorInvocation(String toolName, Map<String, Object> arguments) {
    }

    private record DiagnosticTemplateMatch(int checkIndex, int templateIndex, int score) {
    }

    private Map<String, Object> routingTraceForStep(InterpretationPlan.Step step, ExecutionRequest request) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schemaVersion", AgentProtocolCatalog.ROUTING_TRACE);
        trace.put("plannerVersion", request == null || request.plan() == null ? "unknown" : request.plan().version());
        trace.put("model", "runtime");
        trace.put("source", "interpretation_plan_runtime");
        trace.put("executionTraceId", executionTraceId(request));
        trace.put("stepId", step == null ? null : step.id());
        trace.put("toolName", step == null ? null : step.toolName());
        return trace;
    }

    private boolean hasTargetIdentityConstraint(Map<?, ?> filters) {
        if (filters == null || filters.isEmpty()) {
            return false;
        }
        for (String key : List.of("assetName", "asset_name", "name", "cluster", "namespace", "service",
            "target", "database", "labels")) {
            Object value = filters.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String planGoalSearchText(InterpretationPlan plan) {
        if (plan == null || plan.intent() == null || plan.intent().goal() == null) {
            return null;
        }
        String goal = plan.intent().goal().trim();
        return goal.isBlank() ? null : goal;
    }

    private boolean hasRetrievalSignal(Map<?, ?> filters) {
        if (filters == null || filters.isEmpty()) {
            return false;
        }
        for (String key : List.of("intent", "goal", "query", "q", "bilingualIntent", "bilingualQuery",
            "intentZh", "intentEn", "intentAliases", "keywords", "keyword", "queryTerms", "searchTerms",
            "retrievalSignals")) {
            Object value = filters.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String planText(InterpretationPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (plan.intent() != null) {
            appendText(text, plan.intent().goal());
            appendText(text, plan.intent().type());
        }
        if (plan.context() != null) {
            appendText(text, plan.context().keyFacts());
            appendText(text, plan.context().assumptions());
            appendText(text, plan.context().missingInfo());
            appendText(text, plan.context().constraints());
        }
        return text.toString();
    }

    private void appendText(StringBuilder builder, Object value) {
        if (builder == null || value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                appendText(builder, item);
            }
            return;
        }
        builder.append(' ').append(value);
    }

    private void applyBindings(InterpretationPlan.Step step,
                               InterpretationPlan plan,
                               Map<Integer, StepExecution> completed,
                               Map<String, Object> input,
                               ExecutionRequest request) {
        if (step == null || step.id() == null || plan == null || plan.plan() == null
            || plan.plan().bindings() == null || plan.plan().bindings().isEmpty()) {
            return;
        }
        Map<String, Object> resolvedBindings = new LinkedHashMap<>();
        for (InterpretationPlan.Binding binding : plan.plan().bindings()) {
            if (binding == null || !step.id().equals(binding.to())) {
                continue;
            }
            StepExecution bindingSource = completed == null ? null : completed.get(binding.from());
            if (runtimeOwnsDiagnosticTemplateTransport(
                plan, binding.from(), binding.to(),
                firstText(binding.outputPath(), "") + " " + firstText(binding.inputField(), ""), completed
            ) && (!bindingTargetsBatchChild(binding)
                || bindingSource == null || !bindingSource.success())) {
                log.info("InterpretationPlan ignored model template transport binding because Runtime "
                        + "will compile the authorized template batch: fromStep={}, toStep={}, outputPath={}, inputField={}",
                    binding.from(), binding.to(), binding.outputPath(), binding.inputField());
                continue;
            }
            StepExecution source = bindingSource;
            if (source == null || !source.success()) {
                if (binding.required() == null || binding.required()) {
                    throw new IllegalStateException("BINDING_FAILED: source step not completed for binding "
                        + binding.from() + " -> " + binding.to());
                }
                continue;
            }
            Object value = bindingValue(source, binding, request);
            if (value == null) {
                if (binding.required() == null || binding.required()) {
                    throw new IllegalStateException("BINDING_FAILED: missing output_path " + binding.outputPath()
                        + " from step " + binding.from() + " for input " + binding.inputField());
                }
                continue;
            }
            putInputValue(input, binding.inputField(), value);
            registerResolvedBinding(resolvedBindings, binding.inputField(), value);
            if (isTemplateExecutionTool(step.toolName()) && bindingAssignsTemplateId(binding)
                && !bindingTargetsBatchChild(binding)) {
                putRuntimeTemplateBinding(input, canonicalTemplateId(value), step.toolName(),
                    "plan_binding_from_template_discovery");
            }
        }
        resolveBindingPlaceholders(input, resolvedBindings);
    }

    private void establishRuntimeTemplateBinding(InterpretationPlan.Step step,
                                                 Map<Integer, StepExecution> completed,
                                                 Map<String, Object> input) {
        if (step == null || input == null || !isTemplateExecutionTool(step.toolName())) {
            return;
        }
        String boundTemplateId = runtimeOwnedTemplateId(input);
        if (boundTemplateId == null) {
            boundTemplateId = uniqueCompletedTemplateForExecutor(step.toolName(), completed);
            putRuntimeTemplateBinding(input, boundTemplateId, step.toolName(),
                "unique_completed_template_discovery");
        }
        enforceRuntimeTemplateBinding(step, input);
    }

    private boolean bindingAssignsTemplateId(InterpretationPlan.Binding binding) {
        if (binding == null) {
            return false;
        }
        String inputKey = contractFieldKey(binding.inputField());
        return "templateid".equals(inputKey) || "template".equals(inputKey);
    }

    private boolean bindingTargetsBatchChild(InterpretationPlan.Binding binding) {
        if (binding == null || binding.inputField() == null) {
            return false;
        }
        return pathTokens(binding.inputField()).stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> "calls".equals(value) || "toolcalls".equals(value) || "tool_calls".equals(value));
    }

    private void enforceRuntimeTemplateBinding(InterpretationPlan.Step step, Map<String, Object> input) {
        String boundTemplateId = runtimeOwnedTemplateId(input);
        if (boundTemplateId == null || input == null) {
            return;
        }
        input.put("templateId", boundTemplateId);
        input.put("template", boundTemplateId);
        input.remove("template_id");
        if (step != null) {
            log.debug("InterpretationPlan enforced Runtime-owned template binding stepId={} tool={} templateId={}",
                step.id(), step.toolName(), boundTemplateId);
        }
    }

    private void putRuntimeTemplateBinding(Map<String, Object> input,
                                           String templateId,
                                           String executorTool,
                                           String source) {
        if (input == null || templateId == null || templateId.isBlank()) {
            return;
        }
        input.put("runtimeTemplateBinding", Map.of(
            "schemaVersion", AgentProtocolCatalog.RUNTIME_TEMPLATE_BINDING,
            "source", source,
            "templateId", templateId,
            "executorTool", executorTool == null ? "" : executorTool
        ));
    }

    private String runtimeOwnedTemplateId(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        return canonicalTemplateId(firstValueAtAnyPath(input,
            "$.runtimeTemplateBinding.templateId",
            "$.runtimeTemplateBinding.template_id"));
    }

    private void registerResolvedBinding(Map<String, Object> resolvedBindings, String inputField, Object value) {
        if (resolvedBindings == null || inputField == null || inputField.isBlank()) {
            return;
        }
        String normalized = String.join(".", pathTokens(inputField));
        if (normalized.isBlank()) {
            return;
        }
        resolvedBindings.put(normalized, value);
    }

    private void resolveBindingPlaceholders(Map<String, Object> input, Map<String, Object> resolvedBindings) {
        if (input == null || input.isEmpty() || resolvedBindings == null || resolvedBindings.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : new ArrayList<>(input.entrySet())) {
            entry.setValue(resolveBindingPlaceholderValue(entry.getValue(), resolvedBindings));
        }
    }

    private Object resolveBindingPlaceholderValue(Object value, Map<String, Object> resolvedBindings) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, item) -> resolved.put(String.valueOf(key),
                resolveBindingPlaceholderValue(item, resolvedBindings)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> resolveBindingPlaceholderValue(item, resolvedBindings))
                .toList();
        }
        if (!(value instanceof String text)) {
            return value;
        }
        Matcher matcher = BINDING_PLACEHOLDER_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return value;
        }
        return resolvedBindings.getOrDefault(matcher.group(1), value);
    }

    private void assertNoUnresolvedBindingPlaceholders(Object value) {
        String path = unresolvedBindingPlaceholderPath(value, "$");
        if (path != null) {
            throw new IllegalStateException("BINDING_FAILED: unresolved binding placeholder at " + path);
        }
    }

    private String unresolvedBindingPlaceholderPath(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String unresolved = unresolvedBindingPlaceholderPath(
                    entry.getValue(), path + "." + String.valueOf(entry.getKey()));
                if (unresolved != null) {
                    return unresolved;
                }
            }
            return null;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                String unresolved = unresolvedBindingPlaceholderPath(list.get(index), path + "[" + index + "]");
                if (unresolved != null) {
                    return unresolved;
                }
            }
            return null;
        }
        return value instanceof String text && BINDING_PLACEHOLDER_PATTERN.matcher(text).find()
            ? path
            : null;
    }

    private Object bindingValue(StepExecution source,
                                InterpretationPlan.Binding binding,
                                ExecutionRequest request) {
        if (source == null || binding == null) {
            return null;
        }
        Object value = valueAtPath(source.output(), binding.outputPath());
        if (value != null) {
            return value;
        }
        value = canonicalProtocolValue(source.output(), binding.inputField());
        if (value != null) {
            return value;
        }
        if (environmentContractField(binding.outputPath()) || environmentContractField(binding.inputField())) {
            value = environmentContractValue(source, request, binding.from());
            if (value != null) {
                log.info("InterpretationPlan recovered environment binding from deterministic Agent context: "
                        + "fromStep={}, toStep={}, outputPath={}, inputField={}, env={}",
                    binding.from(), binding.to(), binding.outputPath(), binding.inputField(), value);
                return value;
            }
        }
        if (isTemplateDiscoveryTool(source.toolName()) && bindingTargetsTemplateId(binding)) {
            return firstValueAtAnyPath(source.output(),
                "$.templates[0].templateId",
                "$.templates[0].id",
                "$.templates[0].code",
                "$.results[0].associatedTemplates[0].templateId",
                "$.results[0].associatedTemplates[0].id",
                "$.results[0].associatedTemplates[0].code",
                "$.templateId",
                "$.id",
                "$.code");
        }
        return null;
    }

    private boolean bindingTargetsTemplateId(InterpretationPlan.Binding binding) {
        if (binding == null) {
            return false;
        }
        String outputKey = contractFieldKey(binding.outputPath());
        String inputKey = contractFieldKey(binding.inputField());
        return "templateid".equals(outputKey)
            || "template".equals(outputKey)
            || "templateid".equals(inputKey)
            || "template".equals(inputKey);
    }

    private void putInputValue(Map<String, Object> input, String inputField, Object value) {
        if (input == null || inputField == null || inputField.isBlank()) {
            return;
        }
        List<String> tokens = pathTokens(inputField);
        if (tokens.isEmpty()) {
            return;
        }
        putPathValue(input, tokens, 0, value, inputField);
    }

    @SuppressWarnings("unchecked")
    private void putPathValue(Object container,
                              List<String> tokens,
                              int offset,
                              Object value,
                              String originalPath) {
        if (offset >= tokens.size()) {
            return;
        }
        String token = tokens.get(offset);
        boolean leaf = offset == tokens.size() - 1;
        if (container instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (leaf) {
                map.put(token, value);
                return;
            }
            String nextToken = tokens.get(offset + 1);
            Object child = map.get(token);
            Object mutable = mutablePathContainer(child, nextToken, originalPath);
            map.put(token, mutable);
            putPathValue(mutable, tokens, offset + 1, value, originalPath);
            return;
        }
        if (container instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            int index;
            try {
                index = Integer.parseInt(token);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("BINDING_FAILED: list path requires numeric index at "
                    + originalPath + " but found " + token);
            }
            if (index < 0 || index >= list.size()) {
                throw new IllegalStateException("BINDING_FAILED: list index " + index
                    + " is outside " + originalPath);
            }
            if (leaf) {
                list.set(index, value);
                return;
            }
            String nextToken = tokens.get(offset + 1);
            Object mutable = mutablePathContainer(list.get(index), nextToken, originalPath);
            list.set(index, mutable);
            putPathValue(mutable, tokens, offset + 1, value, originalPath);
            return;
        }
        throw new IllegalStateException("BINDING_FAILED: cannot traverse " + originalPath);
    }

    @SuppressWarnings("unchecked")
    private Object mutablePathContainer(Object existing, String nextToken, String originalPath) {
        if (existing instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        if (existing instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (existing != null) {
            throw new IllegalStateException("BINDING_FAILED: cannot replace scalar while traversing "
                + originalPath);
        }
        return nextToken != null && nextToken.matches("\\d+")
            ? new ArrayList<>()
            : new LinkedHashMap<String, Object>();
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

    private List<String> selectedUrlsFromCompletedWebSearch(Map<Integer, StepExecution> completed) {
        if (completed == null || completed.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        completed.values().stream()
            .filter(step -> step != null && step.success() && isWebDiscoveryTool(step.toolName()))
            .forEach(step -> {
                collectUrls(step.metadata().get("selectedUrls"), urls);
                collectUrls(step.metadata().get("selected_urls"), urls);
                collectUrls(step.output(), urls);
            });
        return urls.stream()
            .filter(url -> url != null && !url.isBlank())
            .map(String::trim)
            .distinct()
            .limit(5)
            .toList();
    }

    private void collectUrls(Object value, List<String> urls) {
        if (value == null || urls == null || urls.size() >= 5) {
            return;
        }
        if (value instanceof String text) {
            if (looksLikeUrl(text)) {
                urls.add(text);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                collectUrls(item, urls);
                if (urls.size() >= 5) {
                    return;
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Object direct = firstMapValue(map, "url", "href", "link", "sourceUrl", "source_url");
            if (direct != null) {
                collectUrls(direct, urls);
            }
            for (Object nested : map.values()) {
                collectUrls(nested, urls);
                if (urls.size() >= 5) {
                    return;
                }
            }
        }
    }

    private Object firstMapValue(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean looksLikeUrl(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return text.startsWith("http://") || text.startsWith("https://");
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

    private boolean isAssetDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return McpToolProtocolRole.ASSET_QUERY.matches(semantic)
            || "database_asset_search".equals(semantic)
            ;
    }

    private boolean isTemplateDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return McpToolProtocolRole.TEMPLATE_QUERY.matches(semantic)
            || semantic.endsWith("_template_search");
    }

    private boolean isSqlQueryExecuteTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "sql_query_execute".equals(semantic) || semantic.endsWith("_sql_query_execute")
            || "sql_script_execute".equals(semantic) || semantic.endsWith("_sql_script_execute");
    }

    private boolean isNewsSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("news_search") || semantic.endsWith("_news_search");
    }

    private boolean isLinuxCommandExecuteTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "linux_command_execute".equals(semantic) || semantic.endsWith("_linux_command_execute");
    }

    private boolean isHttpRequestExecuteTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "http_request_execute".equals(semantic) || semantic.endsWith("_http_request_execute");
    }

    private boolean isApiTemplateExecuteTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return McpToolProtocolRole.TEMPLATE_EXECUTE.matches(semantic)
            && ("api_template_execute".equals(semantic) || semantic.endsWith("_api_template_execute"));
    }

    private boolean isTemplateExecutionTool(String toolName) {
        return isSqlQueryExecuteTool(toolName)
            || isLinuxCommandExecuteTool(toolName)
            || isHttpRequestExecuteTool(toolName)
            || isApiTemplateExecuteTool(toolName);
    }

    private boolean requiresTemplateId(String toolName) {
        return isLinuxCommandExecuteTool(toolName) || isHttpRequestExecuteTool(toolName) || isApiTemplateExecuteTool(toolName);
    }

    private boolean isSqlMetadataSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "sql_metadata_search".equals(semantic) || semantic.endsWith("_sql_metadata_search");
    }

    private boolean isEnterpriseMetadataSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "enterprise_metadata_search".equals(semantic)
            || semantic.endsWith("_enterprise_metadata_search");
    }

    private boolean isRoutingDiscoveryTool(String toolName) {
        return isAssetDiscoveryTool(toolName) || isTemplateDiscoveryTool(toolName);
    }

    private boolean isExecutionContextTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return isSqlQueryExecuteTool(toolName)
            || isSqlMetadataSearchTool(toolName)
            || semantic.equals("database_query")
            || semantic.endsWith("_database_query")
            || semantic.equals("database_query_execute")
            || semantic.endsWith("_database_query_execute")
            || semantic.equals("database_execute")
            || semantic.endsWith("_database_execute")
            || isLinuxCommandExecuteTool(toolName)
            || isHttpRequestExecuteTool(toolName)
            || isApiTemplateExecuteTool(toolName);
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

    private String runId(ExecutionRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.attributes() == null
            ? null : request.attributes().get(AGENT_RUN_ID_ATTRIBUTE);
        if (value != null && !String.valueOf(value).isBlank()) {
            return String.valueOf(value);
        }
        return request.requestId() == null || request.requestId().isBlank()
            ? null : request.requestId();
    }

    private String executionTraceId(ExecutionRequest request, long startedAt) {
        Map<String, Object> attributes = request == null ? null : request.attributes();
        Object configured = firstPresent(attributes, "executionTraceId", "interpretationExecutionTraceId", "__executionTraceId");
        if (configured != null && !String.valueOf(configured).isBlank()) {
            return String.valueOf(configured).trim();
        }
        String runId = runId(request);
        if (runId != null && !runId.isBlank()) {
            return runId + "::interpretation_plan";
        }
        String requestId = request == null ? null : request.requestId();
        if (requestId != null && !requestId.isBlank()) {
            return requestId + "::interpretation_plan::" + startedAt;
        }
        return "interpretation_plan::" + startedAt;
    }

    private String executionTraceId(ExecutionRequest request) {
        Map<String, Object> attributes = request == null ? null : request.attributes();
        Object configured = firstPresent(attributes, "executionTraceId", "interpretationExecutionTraceId", "__executionTraceId");
        return configured == null ? "" : String.valueOf(configured);
    }

    private Map<String, Object> attributesWithProtocol(Map<String, Object> attributes, String executionTraceId) {
        Map<String, Object> values = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        values.put("protocolVersion", InterpretationExecutionProtocol.VERSION);
        values.put("executionTraceId", executionTraceId);
        values.put("interpretationExecutionTraceId", executionTraceId);
        return values;
    }

    private void recordControllerDecision(ExecutionRequest request,
                                          String executionTraceId,
                                          int decisionCount,
                                          DagDecision decision,
                                          DecisionValidation validation,
                                          Set<Integer> remaining,
                                          Set<Integer> completedStepIds) {
        String runId = runId(request);
        if (runStore == null || runId == null || runId.isBlank()) {
            return;
        }
        Map<String, Object> decisionMetadata = decision == null ? Map.of() : decisionMetadata(decision);
        Map<String, Object> guardResult = guardResultMetadata(validation);
        Map<String, Object> metadata = new LinkedHashMap<>(InterpretationExecutionProtocol.protocolMetadata(
            executionTraceId,
            decisionCount,
            "controller_decision"
        ));
        metadata.put("workflow", "interpretation_plan");
        metadata.put("structuredRuntimeObservation", true);
        metadata.put("type", "controller_decision");
        metadata.put("workflowExecutionAttempt", workflowExecutionAttempt(request));
        metadata.put("planExecutionScope", planExecutionScope(request));
        metadata.put("decision", decisionMetadata);
        metadata.put("guardResult", guardResult);
        metadata.put("remainingStepIds", remaining == null ? List.of() : new ArrayList<>(remaining));
        metadata.put("completedStepIds", completedStepIds == null ? List.of() : new ArrayList<>(completedStepIds));
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("controller_decision")
            .source(InterpretationExecutionProtocol.DECISION_OBSERVATION_SOURCE)
            .content("LLM DAG controller decision " + decisionCount + " was "
                + (validation != null && validation.valid() ? "accepted" : "rejected") + " by runtime guard.")
            .metadata(metadata)
            .build());
    }

    private StepExecution validateEdgeContracts(InterpretationPlan plan,
                                                List<StepExecution> waveResults,
                                                Map<Integer, StepExecution> completed,
                                                ExecutionRequest request) {
        if (plan == null || plan.plan() == null || plan.plan().edgeContracts() == null || plan.plan().edgeContracts().isEmpty()) {
            return null;
        }
        Set<Integer> completedNow = waveResults.stream()
            .filter(StepExecution::success)
            .map(StepExecution::stepId)
            .collect(Collectors.toSet());
        for (InterpretationPlan.EdgeContract contract : plan.plan().edgeContracts()) {
            if (contract == null || !completedNow.contains(contract.from())) {
                continue;
            }
            if (indexedEdgeTargetsFinalAnswer(plan, contract)) {
                log.info("InterpretationPlan ignored model data edge into final_answer; final synthesis reads cumulative Runtime evidence directly: fromStep={}, toStep={}, field={}",
                    contract.from(), contract.to(), contract.field());
                continue;
            }
            if (runtimeOwnsDiagnosticTemplateTransport(
                plan, contract.from(), contract.to(), contract.field(), completed
            )) {
                log.info("InterpretationPlan ignored model template transport edge contract because Runtime "
                        + "will compile the authorized template batch: fromStep={}, toStep={}, field={}",
                    contract.from(), contract.to(), contract.field());
                continue;
            }
            StepExecution source = completed.get(contract.from());
            ContractCheck check = checkContract(contract, source, request);
            if (!check.success()) {
                return new StepExecution(
                    contract.to(),
                    "edge_contract",
                    null,
                    false,
                    null,
                    check.message(),
                    null,
                    null,
                    0L
                );
            }
        }
        return null;
    }

    private boolean samePlanExecutionScope(Map<String, Object> metadata, ExecutionRequest request) {
        if (metadata == null || !sameWorkflowExecutionAttempt(
            metadata.get("workflowExecutionAttempt"), workflowExecutionAttempt(request))) {
            return false;
        }
        String currentScope = planExecutionScope(request);
        String storedScope = stringValue(metadata.get("planExecutionScope"));
        if (storedScope != null && !storedScope.isBlank()) {
            return currentScope.equals(storedScope);
        }
        return "0".equals(normalizedWorkflowExecutionAttempt(workflowExecutionAttempt(request)));
    }

    private StepExecution applyRuntimeAssetSelection(StepExecution execution,
                                                     StepReview review,
                                                     Map<String, Object> metadata,
                                                     long startedAt) {
        if (execution == null || review == null || !isAssetDiscoveryTool(execution.toolName())) {
            return execution;
        }
        EvidenceBasedAssetCandidateEvaluator.Evaluation evaluation =
            ASSET_CANDIDATE_EVALUATOR.evaluate(execution.output(), review.metadata());
        metadata.put("runtimeAssetSelectionApplied", evaluation.applied());
        metadata.put("runtimeAssetCandidateCount", evaluation.candidateCount());
        metadata.put("runtimeAssetSelectedCount", evaluation.selectedCount());
        metadata.put("runtimeSelectedAssetIds", evaluation.selectedIds());
        metadata.put("runtimeAssetCandidateEvaluations", evaluation.candidateEvaluations());
        metadata.put("runtimeAssetSelectionReason", evaluation.reason());
        if (!evaluation.applied()) return execution;
        return new StepExecution(
            execution.stepId(), execution.actionType(), execution.toolName(), execution.success(),
            evaluation.output(), execution.errorMessage(), execution.toolExecution(), execution.finalAnswer(),
            elapsed(startedAt), metadata);
    }

    private boolean indexedEdgeTargetsFinalAnswer(InterpretationPlan plan,
                                                  InterpretationPlan.EdgeContract contract) {
        if (plan == null || contract == null || contract.to() == null
            || contract.field() == null
            || !contract.field().matches(".*\\[\\d+\\].*")) {
            return false;
        }
        return plan.steps().stream()
            .filter(Objects::nonNull)
            .anyMatch(step -> Objects.equals(step.id(), contract.to()) && step.finalAnswerAction());
    }

    private boolean runtimeOwnsDiagnosticTemplateTransport(InterpretationPlan plan,
                                                           Integer fromStepId,
                                                           Integer toStepId,
                                                           String field,
                                                           Map<Integer, StepExecution> completed) {
        if (plan == null || plan.plan() == null || fromStepId == null || toStepId == null
            || field == null) {
            return false;
        }
        InterpretationPlan.Step sourceStep = plan.steps().stream()
            .filter(candidate -> candidate != null && fromStepId.equals(candidate.id()))
            .findFirst()
            .orElse(null);
        InterpretationPlan.Step targetStep = plan.steps().stream()
            .filter(candidate -> candidate != null && toStepId.equals(candidate.id()))
            .findFirst()
            .orElse(null);
        if (targetStep == null || !targetStep.mcpToolAction()
            || !isTemplateExecutionTool(targetStep.toolName())) {
            return false;
        }
        String normalizedField = field.toLowerCase(Locale.ROOT);
        if (runtimeOwnedReviewedTemplateBatch(targetStep, completed)
            && (normalizedField.contains("template") || normalizedField.contains("call"))) {
            return true;
        }
        if (plan.plan().diagnosticProfile() == null || sourceStep == null
            || (sourceStep.mcpToolAction() && !isTemplateDiscoveryTool(sourceStep.toolName()))
            || !normalizedField.contains("template")) {
            return false;
        }
        long mappedRequiredChecks = (plan.plan().diagnosticProfile().checks() == null
            ? List.<InterpretationPlan.DiagnosticCheck>of()
            : plan.plan().diagnosticProfile().checks()).stream()
            .filter(Objects::nonNull)
            .filter(check -> !Boolean.FALSE.equals(check.required()))
            .filter(check -> check.stepIds() != null && check.stepIds().contains(toStepId))
            .count();
        if (mappedRequiredChecks < 2 || completed == null || completed.isEmpty()) {
            return false;
        }
        return completed.values().stream()
            .filter(Objects::nonNull)
            .filter(StepExecution::success)
            .filter(execution -> isTemplateDiscoveryTool(execution.toolName()))
            .mapToInt(execution -> templateCandidates(execution.output()).size())
            .sum() >= 2;
    }

    private ContractCheck checkContract(InterpretationPlan.EdgeContract contract, StepExecution source) {
        return checkContract(contract, source, null);
    }

    private ContractCheck checkContract(InterpretationPlan.EdgeContract contract,
                                        StepExecution source,
                                        ExecutionRequest request) {
        Object value = contractValue(source, contract.field());
        if (value == null && environmentContractField(contract.field())) {
            value = environmentContractValue(source, request, contract.from());
            if (value != null) {
                log.info("InterpretationPlan satisfied environment edge contract from deterministic Agent context: "
                        + "fromStep={}, toStep={}, field={}, env={}",
                    contract.from(), contract.to(), contract.field(), value);
            }
        }
        boolean required = contract.required() == null || contract.required();
        if (value == null) {
            return required
                ? new ContractCheck(false, "EDGE_CONTRACT_FAILED: missing required field " + contract.field()
                    + " from step " + contract.from() + " for step " + contract.to())
                : new ContractCheck(true, null);
        }
        String declaredType = contract.type() == null ? "any" : contract.type().trim().toLowerCase();
        String type = canonicalEdgeContractType(contract.field(), declaredType);
        if (!type.equals(declaredType)) {
            log.warn("InterpretationPlan edge contract type normalized field={} declaredType={} canonicalType={} fromStep={} toStep={}",
                contract.field(), declaredType, type, contract.from(), contract.to());
        }
        boolean matches = switch (type) {
            case "any" -> true;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> false;
        };
        if (!matches) {
            return new ContractCheck(false, "EDGE_CONTRACT_FAILED: field " + contract.field()
                + " expected " + type + " but was " + value.getClass().getSimpleName());
        }
        return new ContractCheck(true, null);
    }

    private boolean environmentContractField(String field) {
        String key = contractFieldKey(field);
        return "env".equals(key) || "environment".equals(key);
    }

    private String environmentContractValue(StepExecution source,
                                            ExecutionRequest request,
                                            Integer sourceStepId) {
        if (source == null || !isAssetDiscoveryTool(source.toolName())) {
            return null;
        }
        Object value = firstValueAtAnyPath(source.metadata(),
            "$.resolvedInput.filters.env",
            "$.resolvedInput.filters.environment",
            "$.resolvedInput.executionContext.env",
            "$.resolvedInput.executionContext.environment",
            "$.resolvedInput.env",
            "$.resolvedInput.environment");
        String canonical = canonicalProtocolEnvironment(value == null ? null : String.valueOf(value));
        if (canonical != null) {
            return canonical;
        }
        canonical = agentRuntimeEnvironment(request);
        if (canonical != null) {
            return canonical;
        }
        if (request == null || request.plan() == null || sourceStepId == null) {
            return null;
        }
        InterpretationPlan.Step sourceStep = request.plan().steps().stream()
            .filter(Objects::nonNull)
            .filter(step -> sourceStepId.equals(step.id()))
            .findFirst()
            .orElse(null);
        value = firstValueAtAnyPath(sourceStep == null ? null : sourceStep.input(),
            "$.filters.env", "$.filters.environment", "$.env", "$.environment");
        return canonicalProtocolEnvironment(value == null ? null : String.valueOf(value));
    }

    private String canonicalEdgeContractType(String field, String declaredType) {
        String normalized = field == null ? "" : field.replace("_", "").toLowerCase(Locale.ROOT);
        if ((normalized.contains("parameterschema.") || normalized.contains("inputschema.")
            || normalized.contains("schema.")) && normalized.endsWith(".required")) {
            return "array";
        }
        if ((normalized.contains("parameterschema.") || normalized.contains("inputschema.")
            || normalized.contains("schema.")) && normalized.endsWith(".properties")) {
            return "object";
        }
        return declaredType;
    }

    private ContractCheck checkContract(InterpretationPlan.EdgeContract contract, Object output) {
        return checkContract(contract, new StepExecution(
            contract == null ? null : contract.from(),
            null,
            null,
            true,
            output,
            null,
            null,
            null,
            0L
        ));
    }

    private Object contractValue(StepExecution source, String field) {
        if (source == null) {
            return null;
        }
        if (isWholeStepOutputField(field)) {
            return source.output();
        }
        if (isWebSearchTool(source.toolName()) && "data".equalsIgnoreCase(String.valueOf(field).trim())
            && source.output() != null) {
            return source.output();
        }
        Object value = contractValue(source.output(), field);
        if (value != null) {
            return value;
        }
        if (!isTemplateDiscoveryTool(source.toolName())) {
            return null;
        }
        String key = contractFieldKey(field);
        if ("templateid".equals(key) || "id".equals(key) || "template".equals(key)) {
            return firstValueAtAnyPath(
                source.output(),
                "$.templates[0].templateId",
                "$.templates[0].id",
                "$.templates[0].code",
                "$.results[0].associatedTemplates[0].templateId",
                "$.results[0].associatedTemplates[0].id",
                "$.results[0].associatedTemplates[0].code",
                "$.templateId",
                "$.id",
                "$.code"
            );
        }
        return null;
    }

    private Object contractValue(Object output, String field) {
        if (isWholeStepOutputField(field)) {
            return output;
        }
        Object value = valueAtPath(output, field);
        if (value != null || field == null || field.isBlank()) {
            return value;
        }
        value = canonicalProtocolValue(output, field);
        if (value != null) {
            return value;
        }
        String key = contractFieldKey(field);
        if ("assettype".equals(key) || "asset.type".equals(key)) {
            return firstValueAtAnyPath(
                output,
                "assetType",
                "data.assetType",
                "asset.type",
                "data.asset.type",
                "assets[0].assetType",
                "data.assets[0].assetType",
                "assets[0].asset.type",
                "data.assets[0].asset.type"
            );
        }
        if ("allowedcommandtemplates".equals(key)) {
            return firstValueAtAnyPath(
                output,
                "capabilities.allowedCommandTemplates",
                "assets[0].capabilities.allowedCommandTemplates",
                "data.assets[0].capabilities.allowedCommandTemplates"
            );
        }
        if ("allowedcommandtemplateids".equals(key)) {
            return firstValueAtAnyPath(
                output,
                "capabilities.allowedCommandTemplateIds",
                "assets[0].capabilities.allowedCommandTemplateIds",
                "data.assets[0].capabilities.allowedCommandTemplateIds"
            );
        }
        return null;
    }

    private boolean isWholeStepOutputField(String field) {
        String normalized = field == null ? "" : field.trim();
        if ("output".equalsIgnoreCase(normalized) || "$".equals(normalized) || "$.".equals(normalized)) {
            return true;
        }
        String semanticKey = normalized
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
            .toLowerCase(Locale.ROOT);
        return Set.of(
            "searchresult", "searchresults",
            "queryresult", "queryresults",
            "retrievalresult", "retrievalresults",
            "toolresult", "toolresults",
            "evidence", "evidenceresult", "evidenceresults",
            "搜索结果", "检索结果", "查询结果", "工具结果", "证据结果"
        ).contains(semanticKey);
    }

    /**
     * Resolves logical protocol fields from a canonical asset discovery view when a
     * model emitted a legacy or abbreviated path. Resolution is based on the result
     * shape, not on a concrete MCP tool name, so user-bound tools remain portable.
     */
    private Object canonicalProtocolValue(Object output, String requestedField) {
        if (output == null || requestedField == null || requestedField.isBlank()) {
            return null;
        }
        output = routingCapableOutput(output);
        Object canonicalAsset = firstValueAtAnyPath(output, "$.assets[0].asset");
        if (!(canonicalAsset instanceof Map<?, ?>)) {
            return null;
        }
        String key = contractFieldKey(requestedField);
        return switch (key) {
            case "assetname", "name", "displayname" -> firstValueAtAnyPath(output,
                "$.assets[0].asset.name",
                "$.assets[0].asset.displayName");
            case "env", "environment" -> firstValueAtAnyPath(output,
                "$.assets[0].asset.environment",
                "$.assets[0].asset.env");
            case "databaserole" -> firstValueAtAnyPath(output,
                "$.assets[0].asset.databaseRole",
                "$.assets[0].asset.database_role");
            case "assettype", "asset.type" -> firstValueAtAnyPath(output,
                "$.assets[0].asset.type",
                "$.assets[0].asset.assetType");
            case "toolname" -> firstValueAtAnyPath(output,
                "$.assets[0].asset.toolName",
                "$.assets[0].asset.tool_name");
            default -> null;
        };
    }

    /**
     * Oversized tool evidence may be externalized, but its redacted routing
     * projection remains inline so dependent execution can still target the
     * discovered asset deterministically.
     */
    private Object routingCapableOutput(Object output) {
        Object assets = firstValueAtAnyPath(output, "$.assets");
        if (assets instanceof Iterable<?>) {
            return output;
        }
        Object projection = firstValueAtAnyPath(output, "$.routingProjection");
        return projection instanceof Map<?, ?> ? projection : output;
    }

    private Object firstValueAtAnyPath(Object output, String... paths) {
        if (paths == null) {
            return null;
        }
        for (String path : paths) {
            Object value = valueAtPath(output, path);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String contractFieldKey(String field) {
        List<String> tokens = pathTokens(field).stream()
            .filter(token -> !"data".equals(token))
            .toList();
        if (tokens.isEmpty()) {
            return "";
        }
        String last = tokens.get(tokens.size() - 1);
        if ("type".equals(last) && tokens.size() >= 2 && "asset".equals(tokens.get(tokens.size() - 2))) {
            return "asset.type";
        }
        return last.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String normalizeFieldPath(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        return field.replace("_", "")
            .replace("-", "")
            .replace("$", "")
            .replace("[", "")
            .replace("]", "")
            .replace(".", "")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    private Object valueAtPath(Object output, String path) {
        return valueAtPath(output, path, 0);
    }

    private Object valueAtPath(Object output, String path, int depth) {
        if (output == null || path == null || path.isBlank()) {
            return output;
        }
        if (depth > 6) {
            return null;
        }
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) {
            return valueAtPath(normalized, path, depth + 1);
        }
        Object direct = valueAtPathDirect(output, path);
        if (direct != null) {
            return direct;
        }
        if (output instanceof Map<?, ?> map) {
            for (String wrapper : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
                Object nested = firstMapValue(map, wrapper);
                if (nested != null) {
                    Object value = valueAtPath(nested, path, depth + 1);
                    if (value != null) {
                        return value;
                    }
                }
            }
            Object content = firstMapValue(map, "content");
            if (content instanceof List<?> list) {
                for (Object item : list) {
                    Object text = item instanceof Map<?, ?> itemMap ? firstMapValue(itemMap, "text", "content", "data") : item;
                    Object value = valueAtPath(text, path, depth + 1);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private Object valueAtPathDirect(Object output, String path) {
        Object current = output;
        List<String> parts = pathTokens(path);
        int start = parts.size() > 1 && "data".equals(parts.get(0)) && !(current instanceof Map<?, ?> map && map.containsKey("data"))
            ? 1
            : 0;
        for (int i = start; i < parts.size(); i++) {
            String part = parts.get(i);
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(part));
                } catch (RuntimeException ex) {
                    return null;
                }
            } else {
                if (isTemplateIdAlias(part) && current instanceof String) {
                    return current;
                }
                return null;
            }
        }
        return current;
    }

    private boolean isTemplateIdAlias(String part) {
        return "templateId".equals(part)
            || "template_id".equals(part)
            || "templateCode".equals(part)
            || "code".equals(part);
    }

    private List<String> pathTokens(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        String normalized = path.trim();
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceAll("\\[(\\d+)]", ".$1");
        return List.of(normalized.split("\\.")).stream()
            .map(String::trim)
            .filter(part -> !part.isBlank())
            .toList();
    }

    private boolean allowParallel(InterpretationPlan plan) {
        return plan != null
            && plan.executionPolicy() != null
            && Boolean.TRUE.equals(plan.executionPolicy().allowParallel());
    }

    private DecisionValidation validateDecision(DagDecision decision,
                                                InterpretationPlan plan,
                                                Set<Integer> remaining,
                                                Map<Integer, InterpretationPlan.Step> stepsById,
                                                Set<Integer> completedStepIds) {
        Set<Integer> ready = new LinkedHashSet<>(readyStepIds(remaining, stepsById, completedStepIds));
        return validateDecision(decision, plan, remaining, stepsById, completedStepIds, ready);
    }

    private DecisionValidation validateDecision(DagDecision decision,
                                                InterpretationPlan plan,
                                                Set<Integer> remaining,
                                                Map<Integer, InterpretationPlan.Step> stepsById,
                                                Set<Integer> completedStepIds,
                                                Set<Integer> readyStepIds) {
        if (decision == null) {
            return DecisionValidation.invalid("DAG_DECISION_FAILED", "LLM DAG controller returned no decision");
        }
        String action = normalize(decision.action());
        if (!InterpretationExecutionProtocol.ACTIONS.contains(action)) {
            return DecisionValidation.invalid("DAG_DECISION_REJECTED", "Unsupported DAG controller action: " + decision.action());
        }
        if ("abort".equals(action) || "rewrite_plan".equals(action)) {
            return DecisionValidation.control(action);
        }
        List<Integer> stepIds = safeIntegerList(decision.stepIds()).stream()
            .filter(stepId -> stepId != null)
            .distinct()
            .toList();
        if (stepIds.isEmpty()) {
            return DecisionValidation.invalid("DAG_DECISION_REJECTED", "DAG controller must choose at least one step id");
        }
        if (stepIds.size() > 1 && !allowParallel(plan)) {
            return DecisionValidation.invalid("DAG_DECISION_REJECTED", "DAG controller selected multiple steps but allow_parallel is false");
        }
        if ("execute_step".equals(action) && stepIds.size() > 1) {
            return DecisionValidation.invalid("DAG_DECISION_REJECTED", "execute_step may select only one step");
        }
        List<InterpretationPlan.Step> selected = new ArrayList<>();
        for (Integer stepId : stepIds) {
            if (readyStepIds == null || !readyStepIds.contains(stepId)) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "DAG controller selected step " + stepId + " outside the Runtime Ready set: "
                        + (readyStepIds == null ? List.of() : readyStepIds)
                );
            }
            if (!remaining.contains(stepId)) {
                return DecisionValidation.invalid("DAG_DECISION_REJECTED", "DAG controller selected a step that is not remaining: " + stepId);
            }
            InterpretationPlan.Step step = stepsById.get(stepId);
            if (step == null) {
                return DecisionValidation.invalid("DAG_DECISION_REJECTED", "DAG controller selected unknown step: " + stepId);
            }
            if (!completedStepIds.containsAll(safeIntegerList(step.dependsOn()))) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "DAG controller selected step " + stepId + " before dependencies were satisfied: " + safeIntegerList(step.dependsOn())
                );
            }
            if ("final_answer".equals(action) && !step.finalAnswerAction()) {
                return DecisionValidation.invalid("DAG_DECISION_REJECTED", "final_answer action must select a final_answer step");
            }
            selected.add(step);
        }
        boolean selectedFinalAnswerStep = selected.stream().anyMatch(InterpretationPlan.Step::finalAnswerAction);
        if ("final_answer".equals(action) || selectedFinalAnswerStep) {
            List<Integer> pendingSteps = remaining.stream()
                .filter(stepId -> !stepIds.contains(stepId))
                .sorted()
                .toList();
            if (!pendingSteps.isEmpty()) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "final_answer must be the last executed step and cannot skip remaining steps: " + pendingSteps
                );
            }
        }
        return DecisionValidation.executable(action, selected);
    }

    private Map<String, Object> guardResultMetadata(DecisionValidation validation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("protocolVersion", InterpretationExecutionProtocol.VERSION);
        metadata.put("allowed", validation != null && validation.valid());
        metadata.put("status", validation != null && validation.valid() ? "accepted" : "rejected");
        metadata.put("reason", validation == null || validation.message() == null ? "Runtime guard accepted DAG decision." : validation.message());
        metadata.put("validatedAction", validation == null ? null : validation.action());
        metadata.put("validatedStepIds", validation == null || validation.steps() == null
            ? List.of()
            : validation.steps().stream().map(InterpretationPlan.Step::id).toList());
        return metadata;
    }

    private Map<String, Object> decisionMetadata(DagDecision decision) {
        if (decision == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(decision.metadata() == null ? Map.of() : decision.metadata());
        metadata.put("protocolVersion", firstText(decision.protocolVersion(), InterpretationExecutionProtocol.VERSION));
        metadata.put("action", decision.action());
        metadata.put("stepIds", decision.stepIds() == null ? List.of() : decision.stepIds());
        metadata.put("reason", decision.reason());
        if (decision.finalAnswer() != null && !decision.finalAnswer().isBlank()) {
            metadata.put("finalAnswerPreview", shortText(decision.finalAnswer(), 1000));
        }
        return metadata;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<Integer> safeIntegerList(List<Integer> values) {
        return values == null ? List.of() : values;
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Map<String, Object> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String summarize(Object value) {
        if (value == null) {
            return null;
        }
        return shortText(String.valueOf(value), 3000);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    public record ExecutionRequest(
        InterpretationPlan plan,
        com.chatchat.agents.tool.ToolRegistry toolRegistry,
        List<String> allowedTools,
        String tenantId,
        String requestId,
        String conversationId,
        String userId,
        Map<String, Object> attributes
    ) {
        private ExecutionRequest withPlanAndAttributes(InterpretationPlan nextPlan, Map<String, Object> nextAttributes) {
            return new ExecutionRequest(nextPlan, toolRegistry, allowedTools, tenantId, requestId, conversationId, userId, nextAttributes);
        }
    }

    public record ExecutionResult(
        String status,
        boolean success,
        boolean approvalRequired,
        String errorMessage,
        String finalAnswer,
        List<StepExecution> steps,
        Map<String, Object> metadata,
        long durationMs
    ) {
        private static ExecutionResult failed(String status,
                                              String errorMessage,
                                              List<StepExecution> steps,
                                              Map<String, Object> metadata,
                                              String finalAnswer,
                                              long durationMs) {
            return new ExecutionResult(status, false, false, errorMessage, finalAnswer, steps, metadata, durationMs);
        }

        private static ExecutionResult approvalRequired(List<InterpretationPlanValidator.ValidationIssue> approvals,
                                                        List<StepExecution> steps,
                                                        Map<String, Object> metadata,
                                                        long durationMs) {
            Map<String, Object> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
            values.put("approvalRequests", approvals);
            return new ExecutionResult("approval_required", false, true, "Plan requires approval", null, steps, values, durationMs);
        }
    }

    public record StepExecution(
        Integer stepId,
        String actionType,
        String toolName,
        boolean success,
        Object output,
        String errorMessage,
        ToolRuntimeExecution toolExecution,
        String finalAnswer,
        long durationMs,
        Map<String, Object> metadata
    ) {
        public StepExecution {
            if (metadata == null) {
                metadata = Map.of();
            }
        }

        public StepExecution(
            Integer stepId,
            String actionType,
            String toolName,
            boolean success,
            Object output,
            String errorMessage,
            ToolRuntimeExecution toolExecution,
            String finalAnswer,
            long durationMs
        ) {
            this(stepId, actionType, toolName, success, output, errorMessage, toolExecution, finalAnswer, durationMs, Map.of());
        }

        private StepExecution withMetadata(Map<String, Object> nextMetadata, long nextDurationMs) {
            return new StepExecution(
                stepId,
                actionType,
                toolName,
                success,
                output,
                errorMessage,
                toolExecution,
                finalAnswer,
                nextDurationMs,
                nextMetadata
            );
        }
    }

    /** A materialized node result that may be reused only when its step is unchanged. */
    public record ReusableStep(InterpretationPlan.Step step, StepExecution execution) {
    }

    public interface DagExecutionController {
        DagDecision decide(DagDecisionRequest request);
    }

    public interface StepInputEnricher {
        Map<String, Object> enrich(StepInputEnrichmentRequest request);
    }

    public record StepInputEnrichmentRequest(
        InterpretationPlan.Step step,
        Map<String, Object> input,
        Map<Integer, StepExecution> completed,
        ExecutionRequest executionRequest
    ) {
    }

    public record DagDecisionRequest(
        InterpretationPlan plan,
        Set<Integer> remainingStepIds,
        Set<Integer> readyStepIds,
        Map<Integer, StepExecution> completed,
        List<StepExecution> executions,
        Set<Integer> completedStepIds,
        int decisionCount,
        String protocolVersion,
        String executionTraceId,
        String finalAnswer,
        String decisionPurpose
    ) {
        public DagDecisionRequest(InterpretationPlan plan,
                                  Set<Integer> remainingStepIds,
                                  Map<Integer, StepExecution> completed,
                                  List<StepExecution> executions,
                                  Set<Integer> completedStepIds,
                                  int decisionCount,
                                  String protocolVersion,
                                  String executionTraceId,
                                  String finalAnswer) {
            this(plan, remainingStepIds, remainingStepIds, completed, executions, completedStepIds,
                decisionCount, protocolVersion, executionTraceId, finalAnswer, "LEGACY_ARBITRATION");
        }
    }

    public record DagDecision(
        String protocolVersion,
        String action,
        List<Integer> stepIds,
        String reason,
        String finalAnswer,
        Map<String, Object> metadata
    ) {
        public DagDecision {
            if (protocolVersion == null || protocolVersion.isBlank()) {
                protocolVersion = InterpretationExecutionProtocol.VERSION;
            }
            if (stepIds == null) {
                stepIds = List.of();
            }
            if (metadata == null) {
                metadata = Map.of();
            }
        }

        public static DagDecision executeStep(Integer stepId, String reason) {
            return new DagDecision(InterpretationExecutionProtocol.VERSION, "execute_step", stepId == null ? List.of() : List.of(stepId), reason, null, Map.of());
        }

        public static DagDecision executeParallelSteps(List<Integer> stepIds, String reason) {
            return new DagDecision(InterpretationExecutionProtocol.VERSION, "execute_parallel_steps", stepIds == null ? List.of() : stepIds, reason, null, Map.of());
        }

        public static DagDecision finalAnswer(Integer stepId, String answer, String reason) {
            return new DagDecision(InterpretationExecutionProtocol.VERSION, "final_answer", stepId == null ? List.of() : List.of(stepId), reason, answer, Map.of());
        }

        public static DagDecision abort(String reason) {
            return new DagDecision(InterpretationExecutionProtocol.VERSION, "abort", List.of(), reason, null, Map.of());
        }

        public static DagDecision rewritePlan(String reason) {
            return new DagDecision(InterpretationExecutionProtocol.VERSION, "rewrite_plan", List.of(), reason, null, Map.of());
        }
    }

    private record DecisionValidation(
        boolean valid,
        String status,
        String message,
        String action,
        List<InterpretationPlan.Step> steps
    ) {
        private static DecisionValidation invalid(String status, String message) {
            return new DecisionValidation(false, status, message, null, List.of());
        }

        private static DecisionValidation control(String action) {
            return new DecisionValidation(true, null, null, action, List.of());
        }

        private static DecisionValidation executable(String action, List<InterpretationPlan.Step> steps) {
            return new DecisionValidation(true, null, null, action, steps == null ? List.of() : steps);
        }
    }

    private record SemanticBranch(
        Integer targetStepId,
        List<Integer> candidateStepIds,
        boolean required
    ) {
        private SemanticBranch {
            candidateStepIds = candidateStepIds == null ? List.of() : List.copyOf(candidateStepIds);
        }

        private static SemanticBranch none() {
            return new SemanticBranch(null, List.of(), false);
        }
    }

    public interface StepResultReviewer {
        StepReview review(StepReviewRequest request);
    }

    public record StepReviewRequest(
        InterpretationPlan plan,
        InterpretationPlan.Step step,
        StepExecution execution,
        Map<Integer, StepExecution> completed,
        int attempt,
        int maxAttempts,
        String runId
    ) {
        public StepReviewRequest(InterpretationPlan plan,
                                 InterpretationPlan.Step step,
                                 StepExecution execution,
                                 Map<Integer, StepExecution> completed,
                                 int attempt,
                                 int maxAttempts) {
            this(plan, step, execution, completed, attempt, maxAttempts, null);
        }
    }

    public record StepReview(
        boolean satisfied,
        String reason,
        Map<String, Object> metadata
    ) {
        public StepReview {
            if (metadata == null) {
                metadata = Map.of();
            }
        }

        public static StepReview accepted(String reason, Map<String, Object> metadata) {
            return new StepReview(true, reason, metadata);
        }

        public static StepReview rejected(String reason, Map<String, Object> metadata) {
            return new StepReview(false, reason, metadata);
        }
    }

    private record ContractCheck(
        boolean success,
        String message
    ) {
    }

}
