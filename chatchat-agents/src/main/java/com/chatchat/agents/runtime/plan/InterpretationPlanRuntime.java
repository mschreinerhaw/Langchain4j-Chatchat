package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.common.tool.ToolInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    private final ToolRuntimeService toolRuntimeService;
    private final InterpretationPlanValidator validator;
    private final InterpretationPlanOptimizer optimizer;
    private final Executor executor;

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), null);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     Executor executor) {
        this(toolRuntimeService, validator, new InterpretationPlanOptimizer(), executor);
    }

    public InterpretationPlanRuntime(ToolRuntimeService toolRuntimeService,
                                     InterpretationPlanValidator validator,
                                     InterpretationPlanOptimizer optimizer,
                                     Executor executor) {
        this.toolRuntimeService = toolRuntimeService;
        this.validator = validator == null ? new InterpretationPlanValidator() : validator;
        this.optimizer = optimizer == null ? new InterpretationPlanOptimizer() : optimizer;
        this.executor = executor;
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

        while (!remaining.isEmpty()) {
            List<InterpretationPlan.Step> ready = readySteps(executablePlan, remaining, stepsById, completed.keySet());
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
        if (step.mcpToolAction()) {
            try {
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
                        .parameters(step.input() == null ? Map.of() : step.input())
                        .build())
                    .attributes(attributesForStep(request, step, completed))
                    .build());
                boolean success = execution != null && execution.output() != null && execution.output().isSuccess();
                return new StepExecution(
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
            } catch (RuntimeException ex) {
                return new StepExecution(
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
            }
        }
        if (step.finalAnswerAction()) {
            return new StepExecution(
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
        }
        return new StepExecution(
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
    }

    private Map<String, Object> attributesForStep(ExecutionRequest request,
                                                  InterpretationPlan.Step step,
                                                  Map<Integer, StepExecution> completed) {
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes() == null ? Map.of() : request.attributes());
        attributes.put("interpretationPlanVersion", request.plan().version());
        attributes.put("interpretationPlanStepId", step.id());
        attributes.put("interpretationPlanActionType", step.actionType());
        Map<String, Object> executionPlan = new LinkedHashMap<>();
        executionPlan.put("workflow", "interpretation_plan");
        executionPlan.put("intent", request.plan().intent() == null ? "" : request.plan().intent().goal());
        executionPlan.put("tool", step.toolName());
        executionPlan.put("risk_level", request.plan().intent() == null ? "low" : request.plan().intent().riskLevel());
        executionPlan.put("parameters", step.input() == null ? Map.of() : step.input());
        executionPlan.put("reason", "InterpretationPlan step " + step.id());
        attributes.put("executionPlan", executionPlan);
        if (request.plan().executionPolicy() != null && request.plan().executionPolicy().timeoutMs() != null) {
            attributes.putIfAbsent("toolTimeoutMs", request.plan().executionPolicy().timeoutMs());
        }
        attributes.put("completedPlanStepIds", new ArrayList<>(completed.keySet()));
        return attributes;
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
        long durationMs
    ) {
    }

    private record ContractCheck(
        boolean success,
        String message
    ) {
    }
}
