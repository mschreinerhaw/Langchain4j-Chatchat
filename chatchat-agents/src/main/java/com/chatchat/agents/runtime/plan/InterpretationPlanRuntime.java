package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.evidence.EvidenceExecutionLock;
import com.chatchat.agents.evidence.EvidenceLockGraph;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.AgentRunStep;
import com.chatchat.agents.runtime.AgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler;
import com.chatchat.agents.orchestration.McpToolRouter;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Executes validated InterpretationPlan DAGs against the MCP tool runtime.
 */
@Slf4j
public class InterpretationPlanRuntime {

    private static final String AGENT_RUN_ID_ATTRIBUTE = "__agentRunId";
    private static final String ORIGINAL_USER_QUERY_ATTRIBUTE = "originalUserQuery";
    private static final String AGENT_RUNTIME_ENVIRONMENT_ATTRIBUTE = "agentRuntimeEnvironment";
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
        this.toolRuntimeService = toolRuntimeService;
        this.validator = validator == null ? new InterpretationPlanValidator() : validator;
        this.optimizer = optimizer == null ? new InterpretationPlanOptimizer() : optimizer;
        this.runStore = runStore;
        this.stepResultReviewer = stepResultReviewer;
        this.dagExecutionController = dagExecutionController;
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
        InterpretationPlanOptimizer.OptimizationResult optimization = optimizer.optimize(request.plan());
        InterpretationPlan executablePlan = optimization.plan() == null ? request.plan() : optimization.plan();
        String executionTraceId = executionTraceId(request, startedAt);
        ExecutionRequest executableRequest = request.withPlanAndAttributes(
            executablePlan,
            attributesWithProtocol(request.attributes(), executionTraceId)
        );
        InterpretationPlanValidator.ValidationResult validation = validator.validate(
            executablePlan,
            request.toolRegistry(),
            new LinkedHashSet<>(safeList(request.allowedTools()))
        );
        if (!validation.valid()) {
            return ExecutionResult.failed(
                "INVALID_PLAN",
                validation.errors().stream().map(InterpretationPlanValidator.ValidationIssue::message).collect(Collectors.joining("; ")),
                List.of(),
                Map.of("validationIssues", validation.issues()),
                null,
                elapsed(startedAt)
            );
        }
        if (validation.approvalRequired()) {
            return ExecutionResult.approvalRequired(
                validation.approvalRequests(),
                List.of(),
                Map.of("validationIssues", validation.issues()),
                elapsed(startedAt)
            );
        }
        if (dagExecutionController == null) {
            return ExecutionResult.failed(
                "DAG_CONTROLLER_REQUIRED",
                "InterpretationPlan DAG execution requires an LLM decision controller",
                List.of(),
                Map.of("validationIssues", validation.issues()),
                null,
                elapsed(startedAt)
            );
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
        String finalAnswer = null;
        String runId = runId(executableRequest);
        int decisionCount = 0;

        while (!remaining.isEmpty()) {
            InterpretationPlanEventState eventState = eventState(runId, completed.keySet());
            Set<Integer> completedStepIds = new LinkedHashSet<>(completed.keySet());
            completedStepIds.addAll(eventState.completedStepIds());
            hydrateCompletedExecutionsFromEvents(runId, completedStepIds, completed);
            remaining.removeAll(completedStepIds);
            if (remaining.isEmpty()) {
                break;
            }
            int currentDecisionCount = ++decisionCount;
            DagDecision decision = deterministicReadyToolDecision(
                executablePlan,
                remaining,
                stepsById,
                completed,
                completedStepIds,
                currentDecisionCount,
                executionTraceId
            );
            if (decision == null) {
                decision = dagExecutionController.decide(new DagDecisionRequest(
                    executablePlan,
                    new LinkedHashSet<>(remaining),
                    Map.copyOf(completed),
                    List.copyOf(executions),
                    completedStepIds,
                    currentDecisionCount,
                    InterpretationExecutionProtocol.VERSION,
                    executionTraceId,
                    finalAnswer
                ));
            }
            DecisionValidation decisionValidation = validateDecision(decision, executablePlan, remaining, stepsById, completedStepIds);
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
                return ExecutionResult.failed(
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
                );
            }
            if ("abort".equals(decisionValidation.action())) {
                return ExecutionResult.failed(
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
                );
            }
            if ("rewrite_plan".equals(decisionValidation.action())) {
                return ExecutionResult.failed(
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
                );
            }
            List<InterpretationPlan.Step> selected = applyDecisionParameterProtocols(
                decisionValidation.steps(),
                decision
            );
            List<StepExecution> waveResults = executeWave(selected, executableRequest, completed);
            for (StepExecution execution : waveResults) {
                executions.add(execution);
                completed.put(execution.stepId(), execution);
                remaining.remove(execution.stepId());
                if (execution.finalAnswer() != null && !execution.finalAnswer().isBlank()) {
                    finalAnswer = execution.finalAnswer();
                }
            }
            StepExecution contractFailure = validateEdgeContracts(executablePlan, waveResults, completed);
            if (contractFailure != null) {
                executions.add(contractFailure);
                return ExecutionResult.failed(
                    "EDGE_CONTRACT_FAILED",
                    contractFailure.errorMessage(),
                    executions,
                    Map.of(
                        "failedStepId", contractFailure.stepId(),
                        "optimizationPasses", optimization.appliedPasses()
                    ),
                    finalAnswer,
                    elapsed(startedAt)
                );
            }
            StepExecution failed = waveResults.stream()
                .filter(step -> !step.success())
                .findFirst()
                .orElse(null);
            recordStateUpdate(executableRequest, completed, remaining, waveResults, failed);
            if (failed != null) {
                return ExecutionResult.failed(
                    "STEP_FAILED",
                    failed.errorMessage(),
                    executions,
                    Map.of(
                        "failedStepId", failed.stepId(),
                        "failedTool", failed.toolName(),
                        "optimizationPasses", optimization.appliedPasses()
                    ),
                    finalAnswer,
                    elapsed(startedAt)
                );
            }
        }

        return new ExecutionResult(
            "completed",
            true,
            false,
            null,
            finalAnswer,
            executions,
            Map.of(
                "protocolVersion", InterpretationExecutionProtocol.VERSION,
                "executionTraceId", executionTraceId,
                "stepCount", executions.size(),
                "completedPlanStepIds", new ArrayList<>(completed.keySet()),
                "remainingPlanStepIds", new ArrayList<>(remaining),
                "parallel", allowParallel(executablePlan),
                "decisionCount", decisionCount,
                "llmDagController", true,
                "optimizationPasses", optimization.appliedPasses()
            ),
            elapsed(startedAt)
        );
    }

    private DagDecision deterministicReadyToolDecision(InterpretationPlan plan,
                                                       Set<Integer> remaining,
                                                       Map<Integer, InterpretationPlan.Step> stepsById,
                                                       Map<Integer, StepExecution> completed,
                                                       Set<Integer> completedStepIds,
                                                       int decisionCount,
                                                       String executionTraceId) {
        if (remaining == null || remaining.isEmpty() || stepsById == null || stepsById.isEmpty()) {
            return null;
        }
        List<Integer> readyToolStepIds = remaining.stream()
            .filter(stepId -> stepId != null)
            .sorted()
            .map(stepsById::get)
            .filter(step -> step != null && step.mcpToolAction())
            .filter(step -> completedStepIds != null && completedStepIds.containsAll(safeIntegerList(step.dependsOn())))
            .filter(step -> !requiresModelTemplateParameterProtocol(step, completed))
            .map(InterpretationPlan.Step::id)
            .toList();
        if (readyToolStepIds.isEmpty()) {
            return null;
        }
        List<Integer> selected = allowParallel(plan) ? readyToolStepIds : List.of(readyToolStepIds.get(0));
        String action = selected.size() > 1 ? "execute_parallel_steps" : "execute_step";
        log.info("InterpretationPlan deterministic tool scheduling: traceId={}, decisionCount={}, action={}, stepIds={}",
            executionTraceId, decisionCount, action, selected);
        return new DagDecision(
            InterpretationExecutionProtocol.VERSION,
            action,
            selected,
            "Runtime selected ready mcp_tool step(s) deterministically; required tool execution must not be skipped.",
            null,
            mapOf(
                "runtimeDeterministicScheduling", true,
                "decisionCount", decisionCount,
                "executionTraceId", executionTraceId
            )
        );
    }

    private List<StepExecution> executeWave(List<InterpretationPlan.Step> ready,
                                            ExecutionRequest request,
                                            Map<Integer, StepExecution> completed) {
        if (ready.size() == 1 || !allowParallel(request.plan())) {
            return ready.stream()
                .map(step -> executeStep(step, request, completed))
                .toList();
        }
        List<CompletableFuture<StepExecution>> futures = ready.stream()
            .map(step -> CompletableFuture.supplyAsync(() -> executeStep(step, request, completed)))
            .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private StepExecution executeStep(InterpretationPlan.Step step,
                                      ExecutionRequest request,
                                      Map<Integer, StepExecution> completed) {
        long startedAt = System.currentTimeMillis();
        recordPlanStep(request, step, completed);
        if (step.mcpToolAction()) {
            try {
                Map<String, Object> resolvedInput = resolvedStepInput(step, request, completed);
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
                TemplateExecutorInvocation templateInvocation = templateExecutorInvocation(
                    step,
                    completed,
                    resolvedInput,
                    allowedTools
                );
                if (templateInvocation != null) {
                    executionToolName = templateInvocation.toolName();
                    resolvedInput = templateInvocation.arguments();
                }
                assertNoUnresolvedBindingPlaceholders(resolvedInput);
                log.info("InterpretationPlan step resolved input: traceId={}, stepId={}, tool={}, input={}",
                    executionTraceId(request),
                    step.id(),
                    executionToolName,
                    summarize(resolvedInput));
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
                    .attributes(attributesForStep(request, step, completed, resolvedInput, routingDecision))
                    .build());
                boolean success = execution != null && execution.output() != null && execution.output().isSuccess();
                log.info("InterpretationPlan step tool completed: traceId={}, stepId={}, tool={}, success={}, durationMs={}, error={}, output={}",
                    executionTraceId(request),
                    step.id(),
                    executionToolName,
                    success,
                    elapsed(startedAt),
                    execution == null || execution.output() == null ? null : execution.output().getErrorMessage(),
                    summarize(execution == null || execution.output() == null ? null : execution.output().getData()));
                StepExecution result = new StepExecution(
                    step.id(),
                    step.actionType(),
                    executionToolName,
                    success,
                    execution == null || execution.output() == null ? null : execution.output().getData(),
                    execution == null || execution.output() == null ? "Tool returned no execution" : execution.output().getErrorMessage(),
                    execution,
                    null,
                    elapsed(startedAt)
                );
                if (result.success()) {
                    result = reviewToolResult(request, step, result, completed, startedAt);
                }
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
        recordPlanObservation(request, result, null);
        return result;
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
        metadata.put("evidenceIteration", evidenceIteration(request));
        metadata.put("lifecyclePhase", "observation");
        metadata.put("interpretationPlanStepId", step.stepId());
        metadata.put("evidenceId", evidenceId(request, step));
        metadata.put("interpretationPlanActionType", step.actionType());
        metadata.put("toolName", step.toolName());
        metadata.put("success", step.success());
        metadata.put("durationMs", step.durationMs());
        metadata.put("type", step.success() ? "tool" : "tool_failure");
        if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
            metadata.put("errorMessage", step.errorMessage());
        }
        metadata.putAll(step.metadata() == null ? Map.of() : step.metadata());
        if (step.output() != null) {
            metadata.put("stepOutput", step.output());
            metadata.put("stepOutputPreview", shortText(String.valueOf(step.output()), 4000));
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

    private InterpretationPlanEventState eventState(String runId, Set<Integer> fallbackCompletedStepIds) {
        if (runStore == null || runId == null || runId.isBlank()) {
            return new InterpretationPlanEventState(
                fallbackCompletedStepIds == null ? Set.of() : new LinkedHashSet<>(fallbackCompletedStepIds),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
            );
        }
        return InterpretationPlanEventState.from(runStore.events(runId), fallbackCompletedStepIds);
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
        attributes.put("completedPlanStepIds", new ArrayList<>(completed.keySet()));
        List<String> completedTools = completed.values().stream()
            .filter(execution -> execution != null && execution.success())
            .map(StepExecution::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .distinct()
            .toList();
        attributes.put("workflowCompletedTools", completedTools);
        attributes.put("completedTools", completedTools);
        return attributes;
    }

    private StepExecution reviewToolResult(ExecutionRequest request,
                                           InterpretationPlan.Step step,
                                           StepExecution execution,
                                           Map<Integer, StepExecution> completed,
                                           long startedAt) {
        StepReview localReview = localToolResultReview(step, execution);
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        if (localReview != null) {
            metadata.put("toolResultReviewEnabled", stepResultReviewer != null);
            metadata.put("localDecisionPhase", "fact_check");
            metadata.put("localFactCheckSatisfied", localReview.satisfied());
            metadata.put("localFactCheckReason", localReview.reason());
            metadata.putAll(localReview.metadata() == null ? Map.of() : localReview.metadata());
            if (localReview.satisfied() && stepResultReviewer == null) {
                return execution.withMetadata(metadata, elapsed(startedAt));
            }
            if (localReview.satisfied() && shouldSkipModelReviewAfterLocalFactCheck(metadata)) {
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
                    execution,
                    Map.copyOf(completed),
                    attempt,
                    maxAttempts
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
                if (lastReview.satisfied()) {
                    Map<String, Object> lock = executionLock(step, lastReview);
                    if (!lock.isEmpty()) {
                        metadata.put("executionLock", lock);
                    }
                    return execution.withMetadata(metadata, elapsed(startedAt));
                }
                if (reviewContradictsLocalFacts(lastReview, metadata)) {
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
        if (shouldPreservePartialToolResult(execution)) {
            metadata.put("toolResultReviewPartialAccepted", true);
            metadata.put("toolResultReviewPartialReason", reason);
            metadata.put("toolResultReviewReason",
                "Tool returned structured data and is preserved for final synthesis with limitations: " + reason);
            metadata.put("partialEvidence", true);
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
        return !requiredTemplateParameters(completedTemplateMetadata(completed, templateId)).isEmpty();
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

    private boolean shouldSkipModelReviewAfterLocalFactCheck(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        if (!Boolean.TRUE.equals(metadata.get("localFactCheckHasEvidence"))) {
            return false;
        }
        String evidenceType = stringValue(metadata.get("localFactCheckEvidenceType"));
        if ("financial_data_observations".equals(evidenceType)) {
            Integer returnedCount = integerValue(metadata.get("financialObservationCount"));
            return returnedCount != null && returnedCount > 0;
        }
        if (!"template_discovery".equals(evidenceType)) {
            return false;
        }
        Integer returnedCount = integerValue(metadata.get("templateDiscoveryReturnedCount"));
        return returnedCount != null && returnedCount == 1;
    }

    private boolean shouldPreservePartialToolResult(StepExecution execution) {
        if (execution == null || !execution.success() || execution.output() == null) {
            return false;
        }
        if (!isPreservableStructuredDataTool(execution.toolName())) {
            return false;
        }
        return hasStructuredToolEvidence(execution.output(), 0);
    }

    private boolean isPreservableStructuredDataTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        String raw = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return isStructuredDataToolKey(semantic) || isStructuredDataToolKey(raw);
    }

    private boolean isStructuredDataToolKey(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.equals("sql_query_execute")
            || value.endsWith("_sql_query_execute")
            || value.contains("sql_query_execute")
            || value.equals("sql_script_execute")
            || value.endsWith("_sql_script_execute")
            || value.contains("sql_script_execute")
            || value.equals("database_query")
            || value.equals("database_query_execute")
            || value.endsWith("_database_query_execute")
            || value.contains("database_query_execute");
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
        for (String key : List.of("rows", "columns", "results", "resultSets", "result_sets", "records", "items")) {
            Object value = firstMapValue(map, key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return true;
            }
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
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
            int observationCount = financialObservationCount(execution.output(), 0);
            if (observationCount > 0) {
                return StepReview.accepted(
                    "Unified web_search returned " + observationCount
                        + " governed financial observation row(s); model review is unnecessary.",
                    mapOf(
                        "localFactCheckHasEvidence", true,
                        "localFactCheckEvidenceType", "financial_data_observations",
                        "localFactCheckReason", "web_search returned actual structured market rows, not only asset metadata",
                        "financialObservationCount", observationCount,
                        "financialObservationStepId", step == null ? null : step.id()
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
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
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

    private void hydrateCompletedExecutionsFromEvents(String runId,
                                                      Set<Integer> completedStepIds,
                                                      Map<Integer, StepExecution> completed) {
        if (runStore == null || runId == null || runId.isBlank()
            || completedStepIds == null || completedStepIds.isEmpty() || completed == null) {
            return;
        }
        for (AgentRunEvent event : runStore.events(runId)) {
            if (event == null || event.type() != AgentRunEventType.OBSERVATION_RECORDED) {
                continue;
            }
            Map<String, Object> payload = asStringMap(event.payload());
            Map<String, Object> metadata = asStringMap(payload.get("metadata"));
            Integer stepId = integerValue(firstPresent(metadata, "interpretationPlanStepId", "workflowStepId", "stepId"));
            if (stepId == null || !completedStepIds.contains(stepId) || completed.containsKey(stepId)) {
                continue;
            }
            if (!Boolean.TRUE.equals(booleanValue(firstPresent(metadata, "success", "toolSuccess")))) {
                continue;
            }
            Object output = outputFromObservationMetadata(metadata);
            if (output == null) {
                continue;
            }
            completed.put(stepId, new StepExecution(
                stepId,
                stringValue(firstPresent(metadata, "interpretationPlanActionType", "actionType")),
                stringValue(firstPresent(metadata, "toolName", "source")),
                true,
                output,
                null,
                null,
                null,
                longValue(metadata.get("durationMs"), 0L),
                Map.of("hydratedFromRunStoreObservation", true)
            ));
        }
    }

    private Object outputFromObservationMetadata(Map<String, Object> metadata) {
        Object preview = firstPresent(metadata, "stepOutput", "stepOutputPreview", "output", "data");
        if (preview == null) {
            return null;
        }
        if (!(preview instanceof String text)) {
            return preview;
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

    private Map<String, Object> resolvedStepInput(InterpretationPlan.Step step,
                                                  ExecutionRequest request,
                                                  Map<Integer, StepExecution> completed) {
        Map<String, Object> input = new LinkedHashMap<>(step.input() == null ? Map.of() : step.input());
        InterpretationPlan plan = request == null ? null : request.plan();
        applyBindings(step, plan, completed, input);
        establishRuntimeTemplateBinding(step, completed, input);
        normalizeModelInvocationEnvelope(step, input);
        normalizeWebSearchInput(step, request, input);
        normalizeNewsSearchInput(step, request, input);
        compileDirectToolArguments(step, request, input);
        hydrateExecutionContextFromCompletedAssets(step, completed, input);
        normalizeSqlExecutionContext(step, input);
        Map<Integer, StepExecution> contractContext = resolveTemplateContractFromMcp(step, request, completed, input);
        normalizeTemplateExecutionAlias(step, contractContext, input);
        validateTemplateParameterProtocolPresence(step, request, contractContext, input);
        normalizeTemplateExecutionParameters(step, contractContext, input);
        validateTemplateExecutionArgumentContract(step, input);
        hydrateExecutionContextFromTemplate(step, contractContext, input);
        hydrateSqlMetadataParametersFromMetadataSearch(step, contractContext, input);
        repairTableScopedSqlTemplate(step, contractContext, input);
        enforceAgentRuntimeEnvironment(step, request, input);
        validateRequiredTemplateParameters(step, request, contractContext, input);
        validateRequiredExecutionTemplate(step, input);
        normalizeDiscoveryRoutingInput(step, request, completed, input);
        input.remove("runtimeParameterProtocolApplied");
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
        applyModelTemplateParameterProtocol(step, input);
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
    private void applyModelTemplateParameterProtocol(InterpretationPlan.Step step, Map<String, Object> input) {
        Object value = firstMapValue(input, "parameterProtocol", "parameter_protocol");
        if (value == null) {
            return;
        }
        input.remove("parameterProtocol");
        input.remove("parameter_protocol");
        if (!isTemplateExecutionTool(step.toolName()) || !(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: parameter protocol must be an "
                + "object attached to a template execution step");
        }
        Map<String, Object> protocol = new LinkedHashMap<>((Map<String, Object>) raw);
        String version = stringValue(firstMapValue(protocol, "protocol_version", "protocolVersion"));
        if (!InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION.equals(version)) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: unsupported protocol version " + version);
        }
        Integer protocolStepId = integerValue(firstMapValue(protocol, "step_id", "stepId"));
        if (protocolStepId == null || !protocolStepId.equals(step.id())) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: protocol step_id must match "
                + "the selected execution step " + step.id());
        }
        String templateId = canonicalTemplateId(firstMapValue(protocol, "template_id", "templateId"));
        if (templateId == null) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: template_id must be a scalar string");
        }
        String runtimeOwnedTemplateId = runtimeOwnedTemplateId(input);
        String runtimeTemplateId = runtimeOwnedTemplateId != null ? runtimeOwnedTemplateId
            : canonicalTemplateId(firstValueAtAnyPath(input, "$.templateId", "$.template", "$.template_id"));
        if (runtimeOwnedTemplateId != null && !runtimeOwnedTemplateId.equals(templateId)) {
            log.warn("InterpretationPlan ignored model protocol template override stepId={} tool={} modelTemplateId={} runtimeTemplateId={}",
                step.id(), step.toolName(), templateId, runtimeOwnedTemplateId);
            templateId = runtimeOwnedTemplateId;
            input.put("templateId", runtimeOwnedTemplateId);
            input.put("template", runtimeOwnedTemplateId);
        } else if (runtimeTemplateId != null && !runtimeTemplateId.equals(templateId)) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: protocol template_id " + templateId
                + " does not match the Runtime-bound template " + runtimeTemplateId);
        }
        Object unresolvedValue = firstMapValue(protocol, "unresolved_parameters", "unresolvedParameters");
        List<String> unresolved = stringValues(unresolvedValue);
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INCOMPLETE: unresolved parameters "
                + unresolved + "; rewrite the plan or request the missing values instead of executing");
        }
        Object argumentsValue = firstMapValue(protocol, "arguments", "parameters");
        if (!(argumentsValue instanceof Map<?, ?> rawArguments)) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: arguments must be an object");
        }
        Map<String, Object> parameters = input.get("parameters") instanceof Map<?, ?> existing
            ? new LinkedHashMap<>((Map<String, Object>) existing)
            : new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawArguments.entrySet()) {
            String parameterName = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> rawArgument)) {
                throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: argument " + parameterName
                    + " must contain value, source and evidence");
            }
            Map<String, Object> argument = new LinkedHashMap<>((Map<String, Object>) rawArgument);
            String source = stringValue(argument.get("source"));
            String evidence = stringValue(argument.get("evidence"));
            Object argumentValue = argument.get("value");
            if (!"user_query".equals(source) || evidence == null || argumentValue == null
                || (argumentValue instanceof String text && text.isBlank())) {
                throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_INVALID: argument " + parameterName
                    + " must have a non-empty value, source=user_query and evidence");
            }
            parameters.put(parameterName, argumentValue);
        }
        input.put("parameters", parameters);
        input.put("runtimeParameterProtocolApplied", true);
        log.info("InterpretationPlan accepted model template parameter protocol stepId={} tool={} templateId={} parameterKeys={}",
            step.id(), step.toolName(), templateId, parameters.keySet());
    }

    private void compileDirectToolArguments(InterpretationPlan.Step step,
                                            ExecutionRequest request,
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
            input.putIfAbsent("filtersSchemaVersion", "target_filters.v1");
            input.putIfAbsent("trace", routingTraceForStep(step, request));
        }
        Map<String, Object> semantic = new LinkedHashMap<>(input);
        semantic.remove("purpose");
        ToolArgumentCompiler.CompilationResult compilation = TOOL_ARGUMENT_COMPILER.compile(semantic, schema);
        if (!compilation.valid()) {
            throw new IllegalStateException(compilation.structuredError(step.toolName(), stringValue(input.get("action"))));
        }
        input.clear();
        input.putAll(compilation.parameters());
        if (!compilation.repairs().isEmpty()) {
            log.info("InterpretationPlan compiled direct tool semantic arguments stepId={} tool={} repairs={} compiledKeys={}",
                step.id(), step.toolName(), compilation.repairs(), input.keySet());
        }
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
                ? "api" : "database";
        Map<String, Object> discoveryInput = new LinkedHashMap<>();
        discoveryInput.put("candidates", List.of(Map.of("targetKind", targetKind, "confidence", 1.0)));
        discoveryInput.put("finalDecision", targetKind);
        discoveryInput.put("filters", filters);
        discoveryInput.put("limit", 10);
        discoveryInput.put("trace", Map.of(
            "schemaVersion", "runtime_argument_resolution.v1",
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

    private int financialObservationCount(Object output, int depth) {
        if (output == null || depth > 8) return 0;
        Object normalized = normalizeToolProtocolPayload(output);
        if (normalized != output) return financialObservationCount(normalized, depth + 1);
        if (!(output instanceof Map<?, ?> map)) return 0;
        Integer count = integerValue(firstMapValue(map, "financialObservationCount", "financial_observation_count"));
        if (count != null && count > 0) return count;
        Object datasets = firstMapValue(map, "financialData", "financial_data");
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
            int nested = financialObservationCount(firstMapValue(map, key), depth + 1);
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
    private void hydrateExecutionContextFromTemplate(InterpretationPlan.Step step,
                                                     Map<Integer, StepExecution> completed,
                                                     Map<String, Object> input) {
        if (step == null || input == null || completed == null || completed.isEmpty()
            || !isSqlQueryExecuteTool(step.toolName())) {
            return;
        }
        Object existing = firstMapValue(input, "executionContext", "mcpExecutionContext");
        Map<String, Object> executionContext = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        if (hasConcreteExecutionContext(executionContext)) {
            return;
        }
        Object templateIdValue = firstValueAtAnyPath(input, "$.templateId", "$.template", "$.template_id");
        if (templateIdValue == null || String.valueOf(templateIdValue).isBlank()) {
            return;
        }
        Map<String, Object> template = completedTemplateMetadata(completed, String.valueOf(templateIdValue));
        Object contextValue = firstValueAtAnyPath(template,
            "$.sqlExecutionBinding.executionContext",
            "$.executionContext",
            "$.execution.executionContext");
        if (!(contextValue instanceof Map<?, ?> contextMap) || contextMap.isEmpty()) {
            return;
        }
        contextMap.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(value).isBlank()) {
                executionContext.putIfAbsent(String.valueOf(key), value);
            }
        });
        if (!executionContext.isEmpty()) {
            input.put("executionContext", executionContext);
        }
    }

    private boolean hasConcreteExecutionContext(Map<String, Object> executionContext) {
        if (executionContext == null || executionContext.isEmpty()) {
            return false;
        }
        for (String key : List.of("assetName", "asset_name", "name", "env", "environment", "cluster", "service",
            "target", "database", "databaseType", "dbType", "dialect", "databaseRole", "database_role", "labels")) {
            Object value = executionContext.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
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
            if (sameToolName(allowed, executor)) {
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

    private void normalizeTemplateExecutionAlias(InterpretationPlan.Step step,
                                                 Map<Integer, StepExecution> completed,
                                                 Map<String, Object> input) {
        if (step == null || input == null || !isTemplateExecutionTool(step.toolName())) {
            return;
        }
        Object templateId = firstValueAtAnyPath(input,
            "$.template",
            "$.templateId",
            "$.template_id",
            "$.templateCode",
            "$.template_code",
            "$.commandTemplate",
            "$.command_template",
            "$.selectedTemplate.templateId",
            "$.selectedTemplate.id",
            "$.selectedTemplate.code",
            "$.selected_template.templateId",
            "$.selected_template.id",
            "$.selected_template.code",
            "$.execution.template",
            "$.execution.templateId",
            "$.executionBinding.templateId",
            "$.execution_binding.templateId",
            "$.sqlExecutionBinding.templateId");
        if (templateId == null) {
            templateId = uniqueCompletedTemplateForExecutor(step.toolName(), completed);
        }
        if (templateId == null) {
            return;
        }
        String normalizedTemplateId = canonicalTemplateId(templateId);
        if (normalizedTemplateId == null || normalizedTemplateId.isBlank()) {
            throw new IllegalStateException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: template/templateId must be a "
                + "non-empty scalar string, not a template object, array, schema, or placeholder");
        }
        // These are protocol aliases, not model-owned business values. Always replace them with the
        // canonical scalar so a discovery object can never be serialized as "{templateId=...}".
        input.put("templateId", normalizedTemplateId);
        input.put("template", normalizedTemplateId);
        input.remove("template_id");
        input.remove("selectedTemplate");
        input.remove("selected_template");
        input.remove("parameterSchema");
        input.remove("parameter_schema");
        input.remove("parameterContract");
        input.remove("invocationExample");
        input.putIfAbsent("runtimeTemplateBinding", Map.of(
            "schemaVersion", "runtime_template_binding.v1",
            "source", "template_alias_or_completed_template_discovery",
            "templateId", normalizedTemplateId,
            "executorTool", step.toolName()
        ));
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
            if (sameToolName(value == null ? null : String.valueOf(value), toolName)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void normalizeTemplateExecutionParameters(InterpretationPlan.Step step,
                                                      Map<Integer, StepExecution> completed,
                                                      Map<String, Object> input) {
        if (step == null || input == null || completed == null || completed.isEmpty()
            || !isExecutionContextTool(step.toolName()) || isRoutingDiscoveryTool(step.toolName())) {
            return;
        }
        Object templateId = firstValueAtAnyPath(input,
            "$.templateId",
            "$.template",
            "$.template_id");
        if (templateId == null || String.valueOf(templateId).isBlank()) {
            return;
        }
        Map<String, Object> template = completedTemplateMetadata(completed, String.valueOf(templateId));
        if (template.isEmpty()) {
            return;
        }
        List<String> required = requiredTemplateParameters(template);
        Object existing = input.get("parameters");
        Map<String, Object> parameters = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        if (isJsonSchemaObject(parameters)) {
            parameters = new LinkedHashMap<>();
            input.put("parameters", parameters);
            log.warn("InterpretationPlan removed parameter schema mistakenly bound as execution values stepId={} tool={} templateId={}",
                step.id(), step.toolName(), templateId);
        }
        Object schemaValue = firstValueAtAnyPath(template, "$.parameterSchema", "$.parameter_schema");
        if (schemaValue instanceof Map<?, ?> rawSchema) {
            Map<String, Object> schema = new LinkedHashMap<>((Map<String, Object>) rawSchema);
            ToolArgumentCompiler.CompilationResult compilation = TOOL_ARGUMENT_COMPILER.compile(parameters, schema);
            if (!compilation.repairs().isEmpty() || !parameters.keySet().equals(compilation.parameters().keySet())) {
                log.info("InterpretationPlan compiled semantic arguments against MCP parameter schema stepId={} tool={} templateId={} providedKeys={} compiledKeys={} repairs={}",
                    step.id(), step.toolName(), templateId, parameters.keySet(), compilation.parameters().keySet(), compilation.repairs());
            }
            if (!compilation.valid()) {
                throw new IllegalStateException(compilation.structuredError(step.toolName(), String.valueOf(templateId)));
            }
            parameters = new LinkedHashMap<>(compilation.parameters());
            input.put("parameters", parameters);
        }
        if (required.isEmpty()) {
            input.put("parameters", parameters);
            return;
        }
        boolean changed = false;
        for (String requiredName : required) {
            if (requiredName == null || requiredName.isBlank() || hasNonBlank(parameters, requiredName)) {
                continue;
            }
            Object aliasValue = parameterAliasValue(parameters, requiredName);
            if (aliasValue != null && !String.valueOf(aliasValue).isBlank()) {
                parameters.put(requiredName, aliasValue);
                changed = true;
            }
        }
        if (changed || existing instanceof Map<?, ?>) {
            input.put("parameters", parameters);
        }
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

    private void validateRequiredTemplateParameters(InterpretationPlan.Step step,
                                                    ExecutionRequest request,
                                                    Map<Integer, StepExecution> completed,
                                                    Map<String, Object> input) {
        if (step == null || input == null || completed == null || completed.isEmpty()
            || !isExecutionContextTool(step.toolName()) || isRoutingDiscoveryTool(step.toolName())) {
            return;
        }
        Object templateId = firstValueAtAnyPath(input,
            "$.templateId",
            "$.template",
            "$.template_id");
        if (templateId == null || String.valueOf(templateId).isBlank()) {
            return;
        }
        Map<String, Object> template = completedTemplateMetadata(completed, String.valueOf(templateId));
        if (template.isEmpty()) {
            return;
        }
        List<String> required = requiredTemplateParameters(template);
        if (required.isEmpty()) {
            return;
        }
        Map<String, Object> parameters = input.get("parameters") instanceof Map<?, ?> map
            ? asStringMap(map)
            : Map.of();
        List<String> missing = required.stream()
            .filter(name -> name != null && !name.isBlank())
            .filter(name -> !hasNonBlank(parameters, name))
            .toList();
        if (missing.isEmpty()) {
            return;
        }
        throw new IllegalStateException("TEMPLATE_REQUIRED_PARAMETER_MISSING: template "
            + templateId + " requires "
            + missing.stream().map(name -> "parameters." + name).collect(Collectors.joining(", "))
            + ". Use the selected template's parameterSchema/parameterContract; do not call "
            + step.toolName() + " with empty or incomplete parameters.");
    }

    private boolean requiresTemplateParameterProtocol(ExecutionRequest request) {
        return request != null && request.attributes() != null
            && Boolean.TRUE.equals(request.attributes().get("requireTemplateParameterProtocol"));
    }

    private void validateTemplateParameterProtocolPresence(InterpretationPlan.Step step,
                                                            ExecutionRequest request,
                                                            Map<Integer, StepExecution> completed,
                                                            Map<String, Object> input) {
        if (!requiresTemplateParameterProtocol(request) || step == null || input == null
            || !isTemplateExecutionTool(step.toolName())) {
            return;
        }
        String templateId = canonicalTemplateId(firstValueAtAnyPath(input,
            "$.templateId", "$.template", "$.template_id"));
        if (templateId == null) {
            return;
        }
        List<String> required = requiredTemplateParameters(completedTemplateMetadata(completed, templateId));
        if (!required.isEmpty() && !Boolean.TRUE.equals(input.get("runtimeParameterProtocolApplied"))) {
            throw new IllegalStateException("TEMPLATE_PARAMETER_PROTOCOL_REQUIRED: template " + templateId
                + " declares required parameters " + required
                + "; the DAG controller must analyze the current user query and emit "
                + InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION + " before execution");
        }
    }

    private void validateRequiredExecutionTemplate(InterpretationPlan.Step step, Map<String, Object> input) {
        if (step == null || input == null || !requiresTemplateId(step.toolName())) {
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
            if (id != null && templateId.equals(String.valueOf(id))) {
                return template;
            }
        }
        return Map.of();
    }

    private List<Object> templateCandidates(Object output) {
        List<Object> values = new ArrayList<>();
        addIterable(values, firstValueAtAnyPath(output, "$.templates", "$.data.templates"));
        Object results = firstValueAtAnyPath(output, "$.results", "$.data.results");
        if (results instanceof Iterable<?> iterable) {
            for (Object result : iterable) {
                addIterable(values, firstValueAtAnyPath(result,
                    "$.associatedTemplates",
                    "$.templates",
                    "$.data.associatedTemplates",
                    "$.data.templates"));
            }
        }
        return values;
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

    private Object parameterAliasValue(Map<String, Object> parameters, String requiredName) {
        if (parameters == null || parameters.isEmpty() || requiredName == null || requiredName.isBlank()) {
            return null;
        }
        String requiredKey = canonicalParameterKey(requiredName);
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String key = entry.getKey();
            String providedKey = canonicalParameterKey(key);
            if (key != null && (requiredKey.equals(providedKey)
                || (requiredKey.endsWith("name")
                && requiredKey.substring(0, requiredKey.length() - "name".length()).equals(providedKey)))) {
                return entry.getValue();
            }
        }
        return null;
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
        if (hasUsableNonBlank(context, "assetName", "asset_name", "name")
            && hasUsableNonBlank(context, "env", "environment")) {
            return;
        }
        Map<String, Object> assetContext = firstCompletedAssetExecutionContext(completed);
        if (assetContext.isEmpty()) {
            return;
        }
        assetContext.forEach((key, value) -> putIfAbsentOrPlaceholder(context, key, value));
        input.put("executionContext", context);
    }

    private void putIfAbsentOrPlaceholder(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null || String.valueOf(value).isBlank()) {
            return;
        }
        Object existing = target.get(key);
        if (existing == null || String.valueOf(existing).isBlank() || isJsonPathPlaceholder(existing)) {
            target.put(key, value);
        }
    }

    private boolean hasUsableNonBlank(Map<?, ?> input, String... keys) {
        if (input == null || input.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            Object value = input.get(key);
            if (value != null && !String.valueOf(value).isBlank() && !isJsonPathPlaceholder(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJsonPathPlaceholder(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("$.") || trimmed.startsWith("$[");
    }

    private Map<String, Object> firstCompletedAssetExecutionContext(Map<Integer, StepExecution> completed) {
        if (completed == null || completed.isEmpty()) {
            return Map.of();
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

    private Map<String, Object> assetExecutionContext(Object output) {
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
        if (assetName != null && !String.valueOf(assetName).isBlank()) {
            context.put("assetName", String.valueOf(assetName));
        }
        if (env != null && !String.valueOf(env).isBlank()) {
            context.put("env", String.valueOf(env));
        }
        if (databaseRole != null && !String.valueOf(databaseRole).isBlank()) {
            context.put("databaseRole", String.valueOf(databaseRole));
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
        input.putIfAbsent("filtersSchemaVersion", "target_filters.v1");
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

    private Map<String, Object> routingTraceForStep(InterpretationPlan.Step step, ExecutionRequest request) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schemaVersion", "routing_trace.v1");
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
                               Map<String, Object> input) {
        if (step == null || step.id() == null || plan == null || plan.plan() == null
            || plan.plan().bindings() == null || plan.plan().bindings().isEmpty()) {
            return;
        }
        Map<String, Object> resolvedBindings = new LinkedHashMap<>();
        for (InterpretationPlan.Binding binding : plan.plan().bindings()) {
            if (binding == null || !step.id().equals(binding.to())) {
                continue;
            }
            StepExecution source = completed == null ? null : completed.get(binding.from());
            if (source == null || !source.success()) {
                if (binding.required() == null || binding.required()) {
                    throw new IllegalStateException("BINDING_FAILED: source step not completed for binding "
                        + binding.from() + " -> " + binding.to());
                }
                continue;
            }
            Object value = bindingValue(source, binding);
            if (value == null) {
                if (binding.required() == null || binding.required()) {
                    throw new IllegalStateException("BINDING_FAILED: missing output_path " + binding.outputPath()
                        + " from step " + binding.from() + " for input " + binding.inputField());
                }
                continue;
            }
            putInputValue(input, binding.inputField(), value);
            registerResolvedBinding(resolvedBindings, binding.inputField(), value);
            if (isTemplateExecutionTool(step.toolName()) && bindingAssignsTemplateId(binding)) {
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
            "schemaVersion", "runtime_template_binding.v1",
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

    private Object bindingValue(StepExecution source, InterpretationPlan.Binding binding) {
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

    @SuppressWarnings("unchecked")
    private void putInputValue(Map<String, Object> input, String inputField, Object value) {
        if (input == null || inputField == null || inputField.isBlank()) {
            return;
        }
        List<String> tokens = pathTokens(inputField);
        if (tokens.isEmpty()) {
            return;
        }
        Map<String, Object> current = input;
        for (int i = 0; i < tokens.size() - 1; i++) {
            String token = tokens.get(i);
            Object child = current.get(token);
            if (child instanceof Map<?, ?> map) {
                child = new LinkedHashMap<>(map);
            } else {
                child = new LinkedHashMap<String, Object>();
            }
            current.put(token, child);
            current = (Map<String, Object>) child;
        }
        current.put(tokens.get(tokens.size() - 1), value);
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
        return "asset_query".equals(semantic)
            || "database_asset_search".equals(semantic)
            || semantic.endsWith("_asset_query");
    }

    private boolean isTemplateDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return "template_query".equals(semantic)
            || semantic.endsWith("_template_query")
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
        return "api_template_execute".equals(semantic) || semantic.endsWith("_api_template_execute");
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

    private boolean isRoutingDiscoveryTool(String toolName) {
        return isAssetDiscoveryTool(toolName) || isTemplateDiscoveryTool(toolName);
    }

    private boolean isExecutionContextTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("sql_query_execute")
            || semantic.equals("sql_script_execute")
            || semantic.equals("sql_metadata_search")
            || semantic.equals("database_query")
            || semantic.equals("database_query_execute")
            || semantic.equals("database_execute")
            || semantic.equals("linux_command_execute")
            || semantic.equals("http_request_execute")
            || semantic.equals("api_template_execute");
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
        if (request == null || request.attributes() == null) {
            return null;
        }
        Object value = request.attributes().get(AGENT_RUN_ID_ATTRIBUTE);
        return value == null ? null : String.valueOf(value);
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
                                                Map<Integer, StepExecution> completed) {
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
            StepExecution source = completed.get(contract.from());
            ContractCheck check = checkContract(contract, source);
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

    private ContractCheck checkContract(InterpretationPlan.EdgeContract contract, StepExecution source) {
        Object value = contractValue(source, contract.field());
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
        return "output".equalsIgnoreCase(normalized) || "$".equals(normalized) || "$.".equals(normalized);
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

    public interface DagExecutionController {
        DagDecision decide(DagDecisionRequest request);
    }

    public record DagDecisionRequest(
        InterpretationPlan plan,
        Set<Integer> remainingStepIds,
        Map<Integer, StepExecution> completed,
        List<StepExecution> executions,
        Set<Integer> completedStepIds,
        int decisionCount,
        String protocolVersion,
        String executionTraceId,
        String finalAnswer
    ) {
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

    public interface StepResultReviewer {
        StepReview review(StepReviewRequest request);
    }

    public record StepReviewRequest(
        InterpretationPlan plan,
        InterpretationPlan.Step step,
        StepExecution execution,
        Map<Integer, StepExecution> completed,
        int attempt,
        int maxAttempts
    ) {
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
