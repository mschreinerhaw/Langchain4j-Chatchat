package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.AgentRunStep;
import com.chatchat.agents.runtime.AgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Executes validated InterpretationPlan DAGs against the MCP tool runtime.
 */
public class InterpretationPlanRuntime {

    private static final String AGENT_RUN_ID_ATTRIBUTE = "__agentRunId";

    private final ToolRuntimeService toolRuntimeService;
    private final InterpretationPlanValidator validator;
    private final InterpretationPlanOptimizer optimizer;
    private final AgentRunStore runStore;
    private final StepResultReviewer stepResultReviewer;
    private final DagExecutionController dagExecutionController;

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
            DagDecision decision = dagExecutionController.decide(new DagDecisionRequest(
                executablePlan,
                new LinkedHashSet<>(remaining),
                Map.copyOf(completed),
                List.copyOf(executions),
                completedStepIds,
                ++decisionCount,
                InterpretationExecutionProtocol.VERSION,
                executionTraceId,
                finalAnswer
            ));
            DecisionValidation decisionValidation = validateDecision(decision, executablePlan, remaining, stepsById, completedStepIds);
            recordControllerDecision(
                executableRequest,
                executionTraceId,
                decisionCount,
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
                        "decisionCount", decisionCount,
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
                        "decisionCount", decisionCount,
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
                        "decisionCount", decisionCount,
                        "controllerDecision", decisionMetadata(decision),
                        "guardResult", guardResultMetadata(decisionValidation)
                    ),
                    firstText(decision.finalAnswer(), finalAnswer),
                    elapsed(startedAt)
                );
            }
            List<InterpretationPlan.Step> selected = decisionValidation.steps();
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
                "parallel", allowParallel(executablePlan),
                "decisionCount", decisionCount,
                "llmDagController", true,
                "optimizationPasses", optimization.appliedPasses()
            ),
            elapsed(startedAt)
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
                Map<String, Object> resolvedInput = resolvedStepInput(step, request.plan(), completed);
                ToolRuntimeExecution execution = toolRuntimeService.execute(ToolRuntimeRequest.builder()
                    .toolName(step.toolName())
                    .runtimeMode("interpretation_plan")
                    .requestId(request.requestId())
                    .conversationId(request.conversationId())
                    .tenantId(request.tenantId())
                    .userId(request.userId())
                    .allowedTools(new ArrayList<>(safeList(request.allowedTools())))
                    .toolInput(ToolInput.builder()
                        .requestId(request.requestId())
                        .conversationId(request.conversationId())
                        .userId(request.userId())
                        .parameters(resolvedInput)
                        .build())
                    .attributes(attributesForStep(request, step, completed, resolvedInput))
                    .build());
                boolean success = execution != null && execution.output() != null && execution.output().isSuccess();
                StepExecution result = new StepExecution(
                    step.id(),
                    step.actionType(),
                    step.toolName(),
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
        metadata.put("lifecyclePhase", "observation");
        metadata.put("interpretationPlanStepId", step.stepId());
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
                Set.of()
            );
        }
        return InterpretationPlanEventState.from(runStore.events(runId), fallbackCompletedStepIds);
    }

    private Map<String, Object> attributesForStep(ExecutionRequest request,
                                                  InterpretationPlan.Step step,
                                                  Map<Integer, StepExecution> completed,
                                                  Map<String, Object> resolvedInput) {
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes() == null ? Map.of() : request.attributes());
        attributes.put("interpretationPlanVersion", request.plan().version());
        attributes.put("interpretationPlanStepId", step.id());
        attributes.put("interpretationPlanActionType", step.actionType());
        Map<String, Object> executionPlan = new LinkedHashMap<>();
        executionPlan.put("workflow", "interpretation_plan");
        executionPlan.put("intent", request.plan().intent() == null ? "" : request.plan().intent().goal());
        executionPlan.put("tool", step.toolName());
        executionPlan.put("risk_level", request.plan().intent() == null ? "low" : request.plan().intent().riskLevel());
        executionPlan.put("parameters", resolvedInput == null ? Map.of() : resolvedInput);
        executionPlan.put("reason", "InterpretationPlan step " + step.id());
        attributes.put("executionPlan", executionPlan);
        attributes.put("completedPlanStepIds", new ArrayList<>(completed.keySet()));
        return attributes;
    }

    private StepExecution reviewToolResult(ExecutionRequest request,
                                           InterpretationPlan.Step step,
                                           StepExecution execution,
                                           Map<Integer, StepExecution> completed,
                                           long startedAt) {
        if (stepResultReviewer == null) {
            return execution;
        }
        int maxAttempts = toolResultReviewMaxAttempts(request);
        StepReview lastReview = null;
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        metadata.put("toolResultReviewEnabled", true);
        metadata.put("localDecisionPhase", "local_decision");
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
                    return execution.withMetadata(metadata, elapsed(startedAt));
                }
            }
        }
        String reason = lastReview == null || lastReview.reason() == null || lastReview.reason().isBlank()
            ? "Tool result did not satisfy the plan step after model review."
            : lastReview.reason();
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
        return 3;
    }

    private Map<String, Object> resolvedStepInput(InterpretationPlan.Step step,
                                                  InterpretationPlan plan,
                                                  Map<Integer, StepExecution> completed) {
        Map<String, Object> input = new LinkedHashMap<>(step.input() == null ? Map.of() : step.input());
        applyBindings(step, plan, completed, input);
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

    private void applyBindings(InterpretationPlan.Step step,
                               InterpretationPlan plan,
                               Map<Integer, StepExecution> completed,
                               Map<String, Object> input) {
        if (step == null || step.id() == null || plan == null || plan.plan() == null
            || plan.plan().bindings() == null || plan.plan().bindings().isEmpty()) {
            return;
        }
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
            Object value = valueAtPath(source.output(), binding.outputPath());
            if (value == null) {
                if (binding.required() == null || binding.required()) {
                    throw new IllegalStateException("BINDING_FAILED: missing output_path " + binding.outputPath()
                        + " from step " + binding.from() + " for input " + binding.inputField());
                }
                continue;
            }
            putInputValue(input, binding.inputField(), value);
        }
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
            if (!(child instanceof Map<?, ?>)) {
                child = new LinkedHashMap<String, Object>();
                current.put(token, child);
            }
            current = (Map<String, Object>) child;
        }
        current.put(tokens.get(tokens.size() - 1), value);
    }

    private boolean hasNonBlank(Map<String, Object> input, String... keys) {
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
            ContractCheck check = checkContract(contract, source == null ? null : source.output());
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

    private ContractCheck checkContract(InterpretationPlan.EdgeContract contract, Object output) {
        Object value = valueAtPath(output, contract.field());
        boolean required = contract.required() == null || contract.required();
        if (value == null) {
            return required
                ? new ContractCheck(false, "EDGE_CONTRACT_FAILED: missing required field " + contract.field()
                    + " from step " + contract.from() + " for step " + contract.to())
                : new ContractCheck(true, null);
        }
        String type = contract.type() == null ? "any" : contract.type().trim().toLowerCase();
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

    private Object valueAtPath(Object output, String path) {
        if (output == null || path == null || path.isBlank()) {
            return output;
        }
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
                return null;
            }
        }
        return current;
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
