package com.chatchat.chat.dag;

import com.chatchat.agents.runtime.plan.persistence.NodeAttemptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseNodeAttemptStoreTest {

    @Test
    void assignsNextAttemptNumberAndRejectsStaleOrIllegalTransitions() {
        NodeAttemptRepository repository = mock(NodeAttemptRepository.class);
        NodeAttemptEntity previous = entity("previous", "tenant-a", "run-a", 7, 2, "FAILED");
        when(repository.findTopByTenantIdAndRunIdAndNodeIdOrderByAttemptNumberDesc("tenant-a", "run-a", 7))
            .thenReturn(Optional.of(previous));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            NodeAttemptEntity value = invocation.getArgument(0);
            value.onCreate();
            if (value.getRevision() == null) {
                value.setRevision(0L);
            }
            return value;
        });
        DatabaseNodeAttemptStore store = new DatabaseNodeAttemptStore(repository, new ObjectMapper());

        NodeAttemptStore.AttemptSnapshot created = store.create(new NodeAttemptStore.AttemptCommand(
            "tenant-a", "run-a", "trace-a", "1.0", 7, "definition", "input", Map.of()));

        assertThat(created.attemptNumber()).isEqualTo(3);
        assertThat(created.state()).isEqualTo(NodeAttemptStore.State.CREATED);

        NodeAttemptEntity persisted = entity(created.attemptId(), "tenant-a", "run-a", 7, 3, "READY");
        when(repository.findByTenantIdAndAttemptId("tenant-a", created.attemptId()))
            .thenReturn(Optional.of(persisted));

        assertThatThrownBy(() -> store.transition(
            "tenant-a", created.attemptId(), NodeAttemptStore.State.CREATED,
            NodeAttemptStore.State.RUNNING, "start", Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Stale node attempt transition");

        assertThatThrownBy(() -> store.transition(
            "tenant-a", created.attemptId(), NodeAttemptStore.State.READY,
            NodeAttemptStore.State.COMMITTED, "skip prepare", Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Illegal node attempt transition");

        persisted.setState("PREPARED");
        persisted.onCreate();
        when(repository.findAllByTenantIdAndAttemptIdInOrderByAttemptId(
            "tenant-a", List.of(created.attemptId()))).thenReturn(List.of(persisted));
        when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NodeAttemptStore.BarrierResult barrier = store.commitBarrier(new NodeAttemptStore.BarrierCommand(
            "tenant-a", "run-a", "epoch-1", List.of(created.attemptId()), Map.of("requiredNodeCount", 1)));

        assertThat(barrier.committed()).isTrue();
        assertThat(barrier.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.state()).isEqualTo(NodeAttemptStore.State.COMMITTED);
            assertThat(attempt.attemptId()).isEqualTo(created.attemptId());
        });
        assertThat(persisted.getExecutionEpoch()).isEqualTo("epoch-1");
        assertThat(persisted.getCommittedAt()).isNotNull();

        when(repository.findAllByTenantIdAndRunIdAndStateOrderByCommittedAtAscNodeIdAsc(
            "tenant-a", "run-a", "COMMITTED")).thenReturn(List.of(persisted));
        assertThat(store.supportsRecoveryQueries()).isTrue();
        assertThat(store.committedAttempts("tenant-a", "run-a"))
            .singleElement()
            .satisfies(attempt -> assertThat(attempt.state()).isEqualTo(NodeAttemptStore.State.COMMITTED));
    }

    @Test
    void leaseHeartbeatFencesStaleOwnerAndExpiredRunIsReclaimed() {
        NodeAttemptRepository repository = mock(NodeAttemptRepository.class);
        NodeAttemptEntity ready = entity("attempt-a", "tenant-a", "run-a", 1, 1, "READY");
        when(repository.findByTenantIdAndAttemptId("tenant-a", "attempt-a")).thenReturn(Optional.of(ready));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DatabaseNodeAttemptStore store = new DatabaseNodeAttemptStore(repository, new ObjectMapper());
        Instant start = Instant.parse("2026-08-17T00:00:00Z");

        NodeAttemptStore.LeaseSnapshot lease = store.acquireLease(
            "tenant-a", "attempt-a", "worker-a", start, 5_000);
        assertThat(lease.expiresAt()).isEqualTo(start.plusSeconds(5));
        assertThatThrownBy(() -> store.heartbeat(
            "tenant-a", "attempt-a", "worker-b", lease.leaseToken(), start.plusSeconds(1), 5_000))
            .hasMessageContaining("does not own");

        ready.setState("RUNNING");
        ready.setLeaseExpiresAt(start.minusSeconds(1));
        when(repository.findAllByStateAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
            org.mockito.ArgumentMatchers.eq("RUNNING"), any(), any())).thenReturn(List.of(ready));
        when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(store.reclaimExpiredLeases("recovery-worker", start, 100))
            .singleElement().satisfies(value -> assertThat(value.state()).isEqualTo(NodeAttemptStore.State.FAILED));
        assertThat(ready.getLeaseToken()).isNull();
        assertThat(ready.getStateReason()).contains("lease expired", "recovery-worker");
    }

    private NodeAttemptEntity entity(String id, String tenantId, String runId,
                                     int nodeId, int attemptNumber, String state) {
        NodeAttemptEntity entity = new NodeAttemptEntity();
        entity.setAttemptId(id);
        entity.setTenantId(tenantId);
        entity.setRunId(runId);
        entity.setNodeId(nodeId);
        entity.setAttemptNumber(attemptNumber);
        entity.setState(state);
        entity.setRevision(0L);
        return entity;
    }
}
