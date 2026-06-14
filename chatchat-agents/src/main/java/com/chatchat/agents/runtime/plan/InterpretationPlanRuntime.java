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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Executes validated InterpretationPlan DAGs against the MCP tool runtime.
 */
public class InterpretationPlanRuntime {

    private static final String AGENT_RUN_ID_ATTRIBUTE = "__agentRunId";

    private final ToolRuntimeService toolRuntimeService;
    private final InterpretationPlanValidator validator;
    private final InterpretationPlanOptimizer optimizer;
    private final Executor executor;
    private final AgentRunStore runStore;
    private final StepResultReviewer stepResultReviewer;

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), null, null, null);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     AgentRunStore runStore) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), null, runStore, null);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     AgentRunStore runStore,
                                     StepResultReviewer stepResultReviewer) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), null, runStore, stepResultReviewer);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     Executor executor) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), executor, null, null);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     InterpretationPlanOptimizer optimizer,
                                     Executor executor) {
        this(toolRuntimeService, validator, optimizer, executor, null, null);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     InterpretationPlanOptimizer optimizer,
                                     Executor executor,
                                     AgentRunStore runStore) {
        this(toolRuntimeService, validator, optimizer, executor, runStore, null);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     InterpretationPlanOptimizer optimizer,
                                     Executor executor,
                                     AgentRunStore runStore,
                                     StepResultReviewer stepResultReviewer) {
        this.toolRuntimeService = toolRuntimeService;
        this.validator = validator == null ? new InterpretationPlanValidator() : validator;
        this.optimizer = optimizer == null ? new InterpretationPlanOptimizer() : optimizer;
        this.executor = executor;
        this.runStore = runStore;
        this.stepResultReviewer = stepResultReviewer;
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
        ExecutionRequest executableRequest = request.withPlan(executablePlan);
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
        Executor executionExecutor = resolveExecutor(executableRequest);
        String runId = runId(executableRequest);

        while (!remaining.isEmpty()) {
            InterpretationPlanEventState eventState = eventState(runId, completed.keySet());
            List<InterpretationPlan.Step> ready = readySteps(executablePlan, remaining, stepsById, eventState.completedStepIds());
            if (ready.isEmpty()) {
                return ExecutionResult.failed(
                    "DAG_STALLED",
                    "No executable steps remain; dependencies may be unsatisfied",
                    executions,
                    Map.of("remainingStepIds", new ArrayList<>(remaining)),
                    finalAnswer,
                    elapsed(startedAt)
                );
            }
            if (!allowParallel(executablePlan)) {
                ready = ready.stream().limit(1).toList();
            }
            List<StepExecution> waveResults = executeWave(ready, executableRequest, completed, executionExecutor);
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
                "stepCount", executions.size(),
                "parallel", allowParallel(executablePlan),
                "optimizationPasses", optimization.appliedPasses()
            ),
            elapsed(startedAt)
        );
    }

    private List<StepExecution> executeWave(List<InterpretationPlan.Step> ready,
                                            ExecutionRequest request,
                                            Map<Integer, StepExecution> completed,
                                            Executor executionExecutor) {
        if (ready.size() == 1 || !allowParallel(request.plan())) {
            return ready.stream()
                .map(step -> executeStep(step, request, completed))
                .toList();
        }
        List<CompletableFuture<StepExecution>> futures = ready.stream()
            .map(step -> CompletableFuture.supplyAsync(() -> executeStep(step, request, completed), executionExecutor))
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
                Map<String, Object> resolvedInput = resolvedStepInput(step, completed);
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
                                                  Map<Integer, StepExecution> completed) {
        Map<String, Object> input = new LinkedHashMap<>(step.input() == null ? Map.of() : step.input());
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
            .filter(step -> step != null && step.success() && isWebSearchTool(step.toolName()))
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

    private boolean isCrawlerTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return !isWebSearchTool(toolName)
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

    private List<InterpretationPlan.Step> readySteps(InterpretationPlan plan,
                                                     Set<Integer> remaining,
                                                     Map<Integer, InterpretationPlan.Step> stepsById,
                                                     Set<Integer> completed) {
        return remaining.stream()
            .map(stepsById::get)
            .filter(step -> step != null && completed.containsAll(safeIntegerList(step.dependsOn())))
            .sorted(Comparator
                .comparingDouble((InterpretationPlan.Step step) -> -toolPriority(plan, step))
                .thenComparing(InterpretationPlan.Step::id))
            .toList();
    }

    private double toolPriority(InterpretationPlan plan, InterpretationPlan.Step step) {
        if (plan == null || plan.executionPolicy() == null || plan.executionPolicy().toolPriority() == null
            || step == null || step.toolName() == null) {
            return 0.0;
        }
        Map<String, Double> priority = plan.executionPolicy().toolPriority();
        Double exact = priority.get(step.toolName());
        if (exact != null) {
            return exact;
        }
        return priority.getOrDefault(step.toolName().trim().toLowerCase().replace('-', '_'), 0.0);
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
        String[] parts = path.split("\\.");
        int start = parts.length > 1 && "data".equals(parts[0]) && !(current instanceof Map<?, ?> map && map.containsKey("data"))
            ? 1
            : 0;
        for (int i = start; i < parts.length; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(parts[i]));
                } catch (RuntimeException ex) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private Executor resolveExecutor(ExecutionRequest request) {
        if (executor != null) {
            return executor;
        }
        return ForkJoinPool.commonPool();
    }

    private boolean allowParallel(InterpretationPlan plan) {
        return plan != null
            && plan.executionPolicy() != null
            && Boolean.TRUE.equals(plan.executionPolicy().allowParallel());
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
        private ExecutionRequest withPlan(InterpretationPlan nextPlan) {
            return new ExecutionRequest(nextPlan, toolRegistry, allowedTools, tenantId, requestId, conversationId, userId, attributes);
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
