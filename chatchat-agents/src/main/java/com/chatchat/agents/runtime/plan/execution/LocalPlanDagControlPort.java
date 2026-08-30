package com.chatchat.agents.runtime.plan.execution;

import java.util.Collection;
import java.util.List;

/** In-memory implementation of the same deterministic DAG control session used by Temporal. */
public final class LocalPlanDagControlPort implements PlanDagControlPort {

    private final DeterministicPlanDagStateMachine machine = new DeterministicPlanDagStateMachine();

    @Override
    public Session open(SessionCommand command) {
        return new LocalSession(command);
    }

    private final class LocalSession implements Session {
        private final SessionCommand session;
        private long revision;
        private Snapshot snapshot;
        private boolean closed;

        private LocalSession(SessionCommand session) {
            this.session = session;
            this.snapshot = new Snapshot(
                session.sessionId(), 0L, "OPEN", List.of(), List.of(), List.of(), List.of(), List.of());
        }

        @Override
        public Snapshot synchronize(StateCommand command) {
            ensureOpen();
            revision++;
            List<Integer> ready = machine.ready(
                session.graph(), command.remainingStepIds(), command.completedStepIds());
            snapshot = new Snapshot(
                session.sessionId(), revision,
                command.remainingStepIds().isEmpty() ? "COMPLETED" : "RUNNING",
                command.remainingStepIds(), command.completedStepIds(),
                command.skippedStepIds(), command.failedStepIds(), ready);
            return snapshot;
        }

        @Override
        public DeterministicPlanDagStateMachine.BarrierDecision decideBarrier(
            Collection<DeterministicPlanDagStateMachine.NodeOutcome> outcomes,
            boolean commitIndependentSuccesses) {
            ensureOpen();
            revision++;
            return machine.decideBarrier(outcomes, commitIndependentSuccesses);
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Plan DAG control session is closed: " + session.sessionId());
            }
        }
    }
}
