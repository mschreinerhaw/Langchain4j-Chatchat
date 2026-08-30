package com.chatchat.agents.runtime.plan.execution;

import java.util.Collection;
import java.util.List;

/** Durable control plane for Ready-node state and wave commit decisions. */
public interface PlanDagControlPort {

    Session open(SessionCommand command);

    interface Session extends AutoCloseable {
        Snapshot synchronize(StateCommand command);

        DeterministicPlanDagStateMachine.BarrierDecision decideBarrier(
            Collection<DeterministicPlanDagStateMachine.NodeOutcome> outcomes,
            boolean commitIndependentSuccesses);

        @Override
        void close();
    }

    record SessionCommand(
        String schemaVersion,
        String sessionId,
        DeterministicPlanDagStateMachine.Graph graph
    ) {
        public static final String SCHEMA_VERSION = "plan_dag_control.v1";

        public SessionCommand {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("Plan DAG control session id is required");
            }
            sessionId = sessionId.trim();
            if (graph == null) {
                throw new IllegalArgumentException("Plan DAG graph is required");
            }
        }
    }

    record StateCommand(
        List<Integer> remainingStepIds,
        List<Integer> completedStepIds,
        List<Integer> skippedStepIds,
        List<Integer> failedStepIds
    ) {
        public StateCommand {
            remainingStepIds = copy(remainingStepIds);
            completedStepIds = copy(completedStepIds);
            skippedStepIds = copy(skippedStepIds);
            failedStepIds = copy(failedStepIds);
        }

        private static List<Integer> copy(List<Integer> values) {
            return values == null ? List.of() : values.stream()
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        }
    }

    record Snapshot(
        String sessionId,
        long revision,
        String status,
        List<Integer> remainingStepIds,
        List<Integer> completedStepIds,
        List<Integer> skippedStepIds,
        List<Integer> failedStepIds,
        List<Integer> readyStepIds
    ) {
        public Snapshot {
            remainingStepIds = List.copyOf(remainingStepIds == null ? List.of() : remainingStepIds);
            completedStepIds = List.copyOf(completedStepIds == null ? List.of() : completedStepIds);
            skippedStepIds = List.copyOf(skippedStepIds == null ? List.of() : skippedStepIds);
            failedStepIds = List.copyOf(failedStepIds == null ? List.of() : failedStepIds);
            readyStepIds = List.copyOf(readyStepIds == null ? List.of() : readyStepIds);
        }
    }
}
