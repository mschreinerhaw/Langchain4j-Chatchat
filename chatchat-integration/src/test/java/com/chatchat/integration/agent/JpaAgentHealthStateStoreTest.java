package com.chatchat.integration.agent;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaAgentHealthStateStoreTest {
    @Test void persistsCircuitAndSlaStateAcrossStoreInstances() {
        AgentHealthRepository repository = mock(AgentHealthRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        AtomicReference<AgentHealthEntity> row = new AtomicReference<>();
        when(repository.findForUpdate("remote")).thenAnswer(ignored -> Optional.ofNullable(row.get()));
        when(repository.findById("remote")).thenAnswer(ignored -> Optional.ofNullable(row.get()));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            AgentHealthEntity value = invocation.getArgument(0);
            row.set(value);
            return value;
        });

        var first = new JpaAgentHealthStateStore(repository, manager);
        first.record("remote", true, 250, 1000);
        first.record("remote", true, 250, 2000);
        first.record("remote", true, 250, 3000);

        var recovered = new JpaAgentHealthStateStore(repository, manager);
        assertThat(recovered.read("remote").samples()).isEqualTo(3);
        assertThat(recovered.read("remote").openUntilEpochMs()).isEqualTo(33_000);
        assertThat(recovered.record("remote", false, 50, 4000).openUntilEpochMs()).isZero();
    }
}
