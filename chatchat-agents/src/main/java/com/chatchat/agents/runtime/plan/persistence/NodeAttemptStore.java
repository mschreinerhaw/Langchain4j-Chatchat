package com.chatchat.agents.runtime.plan.persistence;

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

    /** Whether this store can reconcile durable committed Attempts during recovery. */
    default boolean supportsRecoveryQueries() {
        return false;
    }

    /** Returns the durable committed Attempts for one run. */
    default List<AttemptSnapshot> committedAttempts(String tenantId, String runId) {
        return List.of();
    }

    /** Whether this store provides distributed worker leases and stale-owner fencing. */
    default boolean supportsLeases() {
        return false;
    }

    default LeaseSnapshot acquireLease(String tenantId, String attemptId, String workerId,
                                       Instant now, long leaseDurationMs) {
        throw new UnsupportedOperationException("Node attempt leases are not supported");
    }

    default LeaseSnapshot heartbeat(String tenantId, String attemptId, String workerId,
                                    String leaseToken, Instant now, long leaseDurationMs) {
        throw new UnsupportedOperationException("Node attempt leases are not supported");
    }

    /** Fences expired owners and makes their attempts terminal so another worker may retry the node. */
    default List<AttemptSnapshot> reclaimExpiredLeases(String recoveryWorkerId, Instant now, int limit) {
        return List.of();
    }

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

    record LeaseSnapshot(
        String attemptId,
        String workerId,
        String leaseToken,
        Instant heartbeatAt,
        Instant expiresAt
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
