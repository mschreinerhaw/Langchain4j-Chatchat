package com.chatchat.agents.runtime.plan;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Durable boundary for one execution attempt of an immutable DAG node. */
public interface NodeAttemptStore {

    enum State {
        CREATED,
        READY,
        RUNNING,
        PREPARED,
        COMMITTED,
        FAILED,
        CANCELLED,
        SKIPPED;

        public boolean terminal() {
            return Set.of(COMMITTED, FAILED, CANCELLED, SKIPPED).contains(this);
        }

        public boolean mayTransitionTo(State target) {
            if (target == null || terminal()) {
                return false;
            }
            return switch (this) {
                case CREATED -> target == READY || target == FAILED || target == CANCELLED || target == SKIPPED;
                case READY -> target == RUNNING || target == FAILED || target == CANCELLED || target == SKIPPED;
                case RUNNING -> target == PREPARED || target == FAILED || target == CANCELLED;
                case PREPARED -> target == COMMITTED || target == FAILED || target == CANCELLED;
                default -> false;
            };
        }
    }

    AttemptSnapshot create(AttemptCommand command);

    AttemptSnapshot transition(String tenantId,
                               String attemptId,
                               State expectedState,
                               State targetState,
                               String reason,
                               Map<String, Object> metadata);

    /** Atomically commits every prepared node in one scheduler epoch. */
    BarrierResult commitBarrier(BarrierCommand command);

    record AttemptCommand(
        String tenantId,
        String runId,
        String executionTraceId,
        String planVersion,
        Integer nodeId,
        String nodeDefinitionFingerprint,
        String inputFingerprint,
        Map<String, Object> metadata
    ) {
    }

    record AttemptSnapshot(
        String attemptId,
        String tenantId,
        String runId,
        Integer nodeId,
        int attemptNumber,
        State state,
        long revision,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    record BarrierCommand(
        String tenantId,
        String runId,
        String executionEpoch,
        List<String> requiredAttemptIds,
        Map<String, Object> metadata
    ) {
    }

    record BarrierResult(
        String executionEpoch,
        boolean committed,
        List<AttemptSnapshot> attempts
    ) {
    }
}
