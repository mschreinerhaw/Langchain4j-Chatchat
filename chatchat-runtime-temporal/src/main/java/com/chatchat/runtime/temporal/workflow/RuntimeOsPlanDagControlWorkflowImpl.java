package com.chatchat.runtime.temporal.workflow;

import com.chatchat.agents.runtime.plan.execution.DeterministicPlanDagStateMachine;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.runtime.temporal.contract.TemporalPlanDagBarrierCommand;
import io.temporal.workflow.Workflow;

import java.util.List;

/** Deterministic owner of Ready-node state and commit-barrier admission. */
public class RuntimeOsPlanDagControlWorkflowImpl implements RuntimeOsPlanDagControlWorkflow {

    private final DeterministicPlanDagStateMachine machine = new DeterministicPlanDagStateMachine();
    private PlanDagControlPort.SessionCommand session;
    private PlanDagControlPort.Snapshot snapshot = new PlanDagControlPort.Snapshot(
        null, 0L, "PENDING", List.of(), List.of(), List.of(), List.of(), List.of());
    private long revision;
    private boolean closed;

    @Override
    public PlanDagControlPort.Snapshot run(PlanDagControlPort.SessionCommand command) {
        session = command;
        snapshot = new PlanDagControlPort.Snapshot(
            command.sessionId(), revision, "OPEN",
            List.of(), List.of(), List.of(), List.of(), List.of());
        Workflow.await(() -> closed);
        return snapshot;
    }

    @Override
    public PlanDagControlPort.Snapshot synchronize(PlanDagControlPort.StateCommand command) {
        requireOpen();
        revision++;
        List<Integer> ready = machine.ready(
            session.graph(), command.remainingStepIds(), command.completedStepIds());
        snapshot = new PlanDagControlPort.Snapshot(
            session.sessionId(), revision,
            command.remainingStepIds().isEmpty() ? "COMPLETED" : "RUNNING",
            command.remainingStepIds(), command.completedStepIds(),
            command.skippedStepIds(), command.failedStepIds(), ready);
        return snapshot;
    }

    @Override
    public DeterministicPlanDagStateMachine.BarrierDecision decideBarrier(
        TemporalPlanDagBarrierCommand command) {
        requireOpen();
        revision++;
        DeterministicPlanDagStateMachine.BarrierDecision decision = machine.decideBarrier(
            command.outcomes(), command.commitIndependentSuccesses());
        snapshot = new PlanDagControlPort.Snapshot(
            session.sessionId(), revision, "BARRIER_" + decision.action(),
            snapshot.remainingStepIds(), snapshot.completedStepIds(),
            snapshot.skippedStepIds(), decision.failedStepIds(), snapshot.readyStepIds());
        return decision;
    }

    @Override
    public PlanDagControlPort.Snapshot closeSession() {
        requireOpen();
        revision++;
        closed = true;
        snapshot = new PlanDagControlPort.Snapshot(
            session.sessionId(), revision, "CLOSED",
            snapshot.remainingStepIds(), snapshot.completedStepIds(),
            snapshot.skippedStepIds(), snapshot.failedStepIds(), snapshot.readyStepIds());
        return snapshot;
    }

    @Override
    public PlanDagControlPort.Snapshot status() {
        return snapshot;
    }

    private void requireOpen() {
        if (session == null || closed) {
            throw new IllegalStateException("Plan DAG control Workflow is not open");
        }
    }
}
