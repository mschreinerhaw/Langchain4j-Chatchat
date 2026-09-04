package com.chatchat.agents.orchestration.planning.evolution;

import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.runtime.plan.execution.LocalPlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationResult;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceCommand;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepFinalizationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionPort;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionReceipt;
import com.chatchat.agents.runtime.plan.execution.PreparedPlanStep;
import com.chatchat.agents.runtime.plan.execution.DeferredPlanToolExecutionException;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Business implementation behind the three fine-grained plan Activities. */
public final class AgentPlanPhaseActivityCoordinator {
    private final ToolRegistry toolRegistry;
    private final ToolRuntimeService toolRuntimeService;
    private final AgentRunStore runStore;
    private final Operations operations;

    public AgentPlanPhaseActivityCoordinator(ToolRegistry toolRegistry,
                                             ToolRuntimeService toolRuntimeService,
                                             AgentRunStore runStore,
                                             Operations operations) {
        this.toolRegistry = toolRegistry;
        this.toolRuntimeService = toolRuntimeService;
        this.runStore = runStore;
        this.operations = operations;
    }

    public PlanModelArbitrationResult arbitrate(PlanModelArbitrationCommand command) {
        PlanExecutionContinuation state = command.continuation();
        Map<String, Object> context = state.context();
        ChatModel model = operations.model(text(context.get("modelName")));
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = state.completedSteps()
            .stream().filter(Objects::nonNull).filter(step -> step.stepId() != null)
            .collect(Collectors.toMap(InterpretationPlanRuntime.StepExecution::stepId, step -> step,
                (left, right) -> right, LinkedHashMap::new));
        InterpretationPlanRuntime.DagDecision decision = operations.decide(
            model, text(context.get("query")), text(context.get("systemPrompt")),
            new InterpretationPlanRuntime.DagDecisionRequest(
                state.plan(), new LinkedHashSet<>(state.remainingStepIds()),
                new LinkedHashSet<>(command.readyStepIds()), completed, state.completedSteps(),
                new LinkedHashSet<>(completed.keySet()), state.decisionCount() + 1,
                InterpretationExecutionProtocol.VERSION, state.sessionId(),
                state.completedSteps().stream().map(InterpretationPlanRuntime.StepExecution::finalAnswer)
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((ignored, value) -> value).orElse(null), command.purpose()));
        return new PlanModelArbitrationResult(decision.action(), decision.stepIds(),
            parameterOverrides(decision.metadata()), decision.finalAnswer(), decision.reason());
    }

    public PlanStepPreparationResult prepare(PlanStepPreparationCommand command) {
        List<PreparedPlanStep> steps = command.selectedStepIds().stream()
            .map(stepId -> advance(command.continuation(), command.parameterOverrides(), stepId, List.of()))
            .toList();
        return new PlanStepPreparationResult(steps);
    }

    public PreparedPlanStep finalizeStep(PlanStepFinalizationCommand command) {
        return advance(command.continuation(), command.parameterOverrides(),
            command.stepId(), command.receipts());
    }

    private PreparedPlanStep advance(PlanExecutionContinuation state,
                                     Map<Integer, Map<String, Object>> parameterOverrides,
                                     int stepId,
                                     List<PlanToolExecutionReceipt> receipts) {
        Map<String, Object> context = state.context();
        ChatModel model = operations.model(text(context.get("modelName")));
        String query = text(context.get("query"));
        String prompt = text(context.get("systemPrompt"));
        InterpretationPlan executablePlan = applyOverrides(
            state.plan(), parameterOverrides);
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            executablePlan, toolRegistry, strings(context.get("allowedTools")),
            text(context.get("tenantId")), text(context.get("requestId")),
            text(context.get("conversationId")), text(context.get("userId")),
            map(context.get("attributes")));
        PlanToolExecutionPort replayPort = toolCommand -> receipts.stream()
            .filter(receipt -> receipt.command().idempotencyKey().equals(toolCommand.idempotencyKey()))
            .map(PlanToolExecutionReceipt::execution)
            .findFirst()
            .orElseThrow(() -> new DeferredPlanToolExecutionException(toolCommand));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService, new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(toolRegistry), runStore,
            review -> operations.review(model, query, prompt, review),
            decision -> operations.decide(model, query, prompt, decision),
            enrichment -> operations.enrich(model, query, enrichment),
            replayPort, new LocalPlanDagControlPort());
        runtime.setJournalWritesEnabled(false);
        try {
            List<InterpretationPlanRuntime.StepExecution> results = runtime.executeAdmittedWave(
                request, List.of(stepId), state.completedSteps(),
                state.sessionId() + ":decision:" + (state.decisionCount() + 1));
            if (results.size() != 1) {
                throw new IllegalStateException("Prepared step did not produce exactly one result");
            }
            InterpretationPlanRuntime.StepExecution result = results.get(0);
            return new PreparedPlanStep(result.stepId(), result.actionType(), result.toolName(),
                null, 1, 1L, false, "not_applicable", result, result.metadata());
        } catch (DeferredPlanToolExecutionException deferred) {
            InterpretationPlan.Step definition = executablePlan.steps().stream()
                .filter(step -> Objects.equals(step.id(), stepId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown prepared step " + stepId));
            return new PreparedPlanStep(stepId, definition.actionType(), definition.toolName(),
                deferred.command(), 1, 300L, false,
                "external tool calls are not assumed idempotent", null,
                Map.of("phase", "AWAITING_TOOL_CHILD", "receiptCount", receipts.size()));
        }
    }

    public PlanNodePersistenceResult persist(PlanNodePersistenceCommand command) {
        PlanExecutionContinuation current = command.continuation();
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = current.completedSteps()
            .stream().filter(Objects::nonNull).filter(step -> step.stepId() != null)
            .collect(Collectors.toMap(InterpretationPlanRuntime.StepExecution::stepId, step -> step,
                (left, right) -> right, LinkedHashMap::new));
        command.waveResults().stream().filter(Objects::nonNull)
            .filter(step -> step.stepId() != null)
            .forEach(step -> completed.put(step.stepId(), step));
        Set<Integer> remaining = new LinkedHashSet<>(current.remainingStepIds());
        command.waveResults().stream().filter(Objects::nonNull)
            .map(InterpretationPlanRuntime.StepExecution::stepId)
            .filter(Objects::nonNull).forEach(remaining::remove);
        Set<Integer> failed = new LinkedHashSet<>(current.failedStepIds());
        command.waveResults().stream().filter(Objects::nonNull)
            .filter(step -> !step.success()).map(InterpretationPlanRuntime.StepExecution::stepId)
            .filter(Objects::nonNull).forEach(failed::add);
        PlanExecutionContinuation next = new PlanExecutionContinuation(
            current.schemaVersion(), current.sessionId(), current.plan(),
            new ArrayList<>(remaining), new ArrayList<>(completed.values()),
            current.skippedStepIds(), new ArrayList<>(failed),
            current.decisionCount() + 1, current.context());
        String status = failed.isEmpty()
            ? (remaining.isEmpty() ? "COMPLETED" : "RUNNING") : "FAILED";
        return new PlanNodePersistenceResult(next, status);
    }

    private Map<Integer, Map<String, Object>> parameterOverrides(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("parameterOverrides");
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            try { result.put(Integer.parseInt(String.valueOf(key)), map(value)); }
            catch (NumberFormatException ignored) { }
        });
        return Map.copyOf(result);
    }

    private InterpretationPlan applyOverrides(
        InterpretationPlan source, Map<Integer, Map<String, Object>> overrides) {
        if (source == null || overrides == null || overrides.isEmpty()) return source;
        List<InterpretationPlan.Step> steps = source.steps().stream().map(step -> {
            Map<String, Object> override = overrides.get(step.id());
            if (override == null || override.isEmpty()) return step;
            Map<String, Object> input = new LinkedHashMap<>(step.input());
            input.putAll(override);
            return new InterpretationPlan.Step(step.id(), step.actionType(), step.toolName(),
                input, step.dependsOn(), step.outputContract(), step.validation());
        }).toList();
        InterpretationPlan.Plan plan = source.plan();
        return new InterpretationPlan(source.version(), source.intent(), source.context(),
            new InterpretationPlan.Plan(steps, plan.edgeContracts(), plan.dependencyContracts(),
                plan.bindings(), plan.stability(), plan.diagnosticProfile(),
                plan.conditionalEdges(), plan.branchGroups()),
            source.executionPolicy(), source.review());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::valueOf)
            .filter(text -> !text.isBlank()).toList();
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value); }

    public interface Operations {
        ChatModel model(String modelName);
        InterpretationPlanRuntime.DagDecision decide(ChatModel model, String query, String prompt,
                                                     InterpretationPlanRuntime.DagDecisionRequest request);
        InterpretationPlanRuntime.StepReview review(ChatModel model, String query, String prompt,
                                                    InterpretationPlanRuntime.StepReviewRequest request);
        Map<String, Object> enrich(ChatModel model, String query,
                                   InterpretationPlanRuntime.StepInputEnrichmentRequest request);
        PlanToolExecutionPort toolPort();
    }
}
