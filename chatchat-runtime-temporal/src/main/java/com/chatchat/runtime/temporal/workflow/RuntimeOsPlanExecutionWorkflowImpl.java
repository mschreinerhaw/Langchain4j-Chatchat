package com.chatchat.runtime.temporal.workflow;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.DeterministicPlanDagStateMachine;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationResult;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceCommand;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepFinalizationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionReceipt;
import com.chatchat.agents.runtime.plan.execution.PreparedPlanStep;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.runtime.temporal.activity.RuntimeOsPlanStageActivity;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionCommand;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionResult;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic owner of the complete Ready-wave scheduling loop. */
public class RuntimeOsPlanExecutionWorkflowImpl implements RuntimeOsPlanExecutionWorkflow {

    private final DeterministicPlanDagStateMachine machine = new DeterministicPlanDagStateMachine();

    @Override
    public TemporalPlanExecutionResult execute(TemporalPlanExecutionCommand command) {
        RuntimeOsPlanStageActivity stages = Workflow.newActivityStub(
            RuntimeOsPlanStageActivity.class, activityOptions(command));
        PlanExecutionContinuation state = command.continuation();
        DeterministicPlanDagStateMachine.Graph graph = machine.compile(state.plan());
        List<InterpretationPlanRuntime.StepExecution> executions =
            new ArrayList<>(state.completedSteps());
        String finalAnswer = executions.stream()
            .map(InterpretationPlanRuntime.StepExecution::finalAnswer)
            .filter(value -> value != null && !value.isBlank())
            .reduce((ignored, value) -> value).orElse(null);
        long revision = 0L;

        while (!state.remainingStepIds().isEmpty()) {
            List<Integer> completedIds = executions.stream()
                .filter(step -> step != null && step.success() && step.stepId() != null)
                .map(InterpretationPlanRuntime.StepExecution::stepId)
                .distinct().sorted().toList();
            List<Integer> ready = machine.ready(graph, state.remainingStepIds(), completedIds);
            if (ready.isEmpty()) {
                return terminal("DAG_NO_PROGRESS", state, executions, finalAnswer,
                    "Unfinished DAG contains no Ready nodes");
            }

            PlanModelArbitrationResult decision = stages.arbitrate(
                new PlanModelArbitrationCommand(
                    PlanModelArbitrationCommand.SCHEMA_VERSION,
                    state, ready, "READY_WAVE_ARBITRATION"));
            if (Set.of("abort", "rewrite_plan").contains(decision.action())) {
                return terminal(decision.action().toUpperCase(), state, executions,
                    firstText(decision.finalAnswer(), finalAnswer), decision.reason());
            }
            validateSelectedReadyNodes(ready, decision.selectedStepIds());

            PlanStepPreparationResult prepared = stages.prepare(
                new PlanStepPreparationCommand(
                    PlanStepPreparationCommand.SCHEMA_VERSION,
                    state, decision.selectedStepIds(), decision.parameterOverrides()));
            validatePreparedWave(decision.selectedStepIds(), prepared);
            List<InterpretationPlanRuntime.StepExecution> waveResults = new ArrayList<>();
            for (PreparedPlanStep step : prepared.steps()) {
                InterpretationPlanRuntime.StepExecution result = finishPreparedStep(
                    stages, state, decision.parameterOverrides(), step);
                waveResults.add(result);
                if (result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
                    finalAnswer = result.finalAnswer();
                }
            }
            if (waveResults.isEmpty()) {
                return terminal("EMPTY_PREPARED_WAVE", state, executions, finalAnswer,
                    "Step preparation returned no executable nodes");
            }

            DeterministicPlanDagStateMachine.BarrierDecision barrier = machine.decideBarrier(
                waveResults.stream().map(result ->
                    new DeterministicPlanDagStateMachine.NodeOutcome(
                        result.stepId(), result.success())).toList(),
                command.commitIndependentSuccesses());
            revision++;
            PlanNodePersistenceResult persisted = stages.persist(
                new PlanNodePersistenceCommand(
                    PlanNodePersistenceCommand.SCHEMA_VERSION,
                    state, revision, barrier.action(), waveResults));
            PlanExecutionContinuation previous = state;
            state = persisted.continuation();
            validateContinuationProgress(previous, state);
            executions = new ArrayList<>(state.completedSteps());
            if ("FAILED".equalsIgnoreCase(persisted.status())) {
                return terminal("FAILED", state, executions, finalAnswer,
                    "Node persistence rejected the wave");
            }
        }
        return terminal("COMPLETED", state, executions, finalAnswer, null);
    }

    private InterpretationPlanRuntime.StepExecution finishPreparedStep(
        RuntimeOsPlanStageActivity stages,
        PlanExecutionContinuation state,
        Map<Integer, Map<String, Object>> parameterOverrides,
        PreparedPlanStep initial) {
        PreparedPlanStep current = initial;
        List<PlanToolExecutionReceipt> receipts = new ArrayList<>();
        int invocationLimit = 4;
        while (current.immediateResult() == null) {
            if (current.toolCommand() == null || receipts.size() >= invocationLimit) {
                throw new IllegalStateException("Prepared step exceeded its Tool Child invocation limit");
            }
            ToolRuntimeExecution execution = executeToolChild(state, current);
            receipts.add(new PlanToolExecutionReceipt(current.toolCommand(), execution));
            current = stages.finalizeStep(new PlanStepFinalizationCommand(
                PlanStepFinalizationCommand.SCHEMA_VERSION, state, current.stepId(),
                parameterOverrides, receipts));
            if (current.stepId() != initial.stepId()) {
                throw new IllegalStateException("Step finalization changed the prepared node identity");
            }
        }
        return current.immediateResult();
    }

    private ToolRuntimeExecution executeToolChild(
        PlanExecutionContinuation state, PreparedPlanStep step) {
        ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
            .setWorkflowId(Workflow.getInfo().getWorkflowId() + "::step::" + step.stepId()
                + "::decision::" + state.decisionCount())
            .setTaskQueue(Workflow.getInfo().getTaskQueue())
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
            .build();
        RuntimeOsToolExecutionWorkflow tool = Workflow.newChildWorkflowStub(
            RuntimeOsToolExecutionWorkflow.class, options);
        return tool.execute(new TemporalToolActivityCommand(
            step.toolCommand().request(), step.toolCommand().idempotencyKey(),
            step.toolActivityMaximumAttempts(), step.toolActivityStartToCloseSeconds(),
            step.toolActivityRetrySafe(), step.toolActivityRetryReason()));
    }

    private ActivityOptions activityOptions(TemporalPlanExecutionCommand command) {
        return ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(command.activityStartToCloseSeconds()))
            .setHeartbeatTimeout(Duration.ofSeconds(command.activityHeartbeatSeconds()))
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build();
    }

    private void validateSelectedReadyNodes(List<Integer> ready, List<Integer> selected) {
        Set<Integer> legal = new LinkedHashSet<>(ready);
        if (selected == null || selected.isEmpty() || !legal.containsAll(selected)) {
            throw new IllegalStateException("Model arbitration selected nodes outside the Ready wave");
        }
    }

    private void validatePreparedWave(List<Integer> selected,
                                      PlanStepPreparationResult prepared) {
        List<Integer> preparedIds = prepared == null ? List.of() : prepared.steps().stream()
            .map(PreparedPlanStep::stepId).distinct().sorted().toList();
        List<Integer> selectedIds = selected == null ? List.of() : selected.stream()
            .distinct().sorted().toList();
        if (!preparedIds.equals(selectedIds)) {
            throw new IllegalStateException(
                "Step preparation output does not match the admitted Ready nodes");
        }
    }

    private void validateContinuationProgress(PlanExecutionContinuation previous,
                                              PlanExecutionContinuation next) {
        if (next == null || !previous.sessionId().equals(next.sessionId())) {
            throw new IllegalStateException("Node persistence changed the plan session identity");
        }
        if (next.decisionCount() <= previous.decisionCount()
            || next.remainingStepIds().size() >= previous.remainingStepIds().size()) {
            throw new IllegalStateException("Node persistence returned a non-progressing continuation");
        }
        if (!previous.remainingStepIds().containsAll(next.remainingStepIds())) {
            throw new IllegalStateException("Node persistence introduced unknown remaining nodes");
        }
    }

    private TemporalPlanExecutionResult terminal(
        String status, PlanExecutionContinuation state,
        List<InterpretationPlanRuntime.StepExecution> executions,
        String finalAnswer, String reason) {
        return new TemporalPlanExecutionResult(status, state, executions, finalAnswer, reason);
    }

    private String firstText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
