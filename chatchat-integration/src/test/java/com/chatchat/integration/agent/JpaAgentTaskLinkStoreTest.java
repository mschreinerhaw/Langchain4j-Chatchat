package com.chatchat.integration.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaAgentTaskLinkStoreTest {
    @Test void persistsAndRecoversTaskIdentityWithoutCredentials() {
        AgentTaskLinkRepository repository = mock(AgentTaskLinkRepository.class);
        JpaAgentTaskLinkStore store = new JpaAgentTaskLinkStore(repository);
        var link = new AgentTaskLinkStore.TaskLink("exec-1", "tenant-1", "agent-1",
            "remote-task", "remote-context", 1000);
        when(repository.findById("exec-1")).thenReturn(Optional.of(new AgentTaskLinkEntity(link)));

        store.save(link);

        assertThat(store.find("exec-1")).contains(link);
        verify(repository).saveAndFlush(any(AgentTaskLinkEntity.class));
    }

    @Test void expiresOnlyLinksOlderThanCutoff() {
        AgentTaskLinkRepository repository = mock(AgentTaskLinkRepository.class);
        JpaAgentTaskLinkStore store = new JpaAgentTaskLinkStore(repository);

        store.deleteExpired(1234);

        verify(repository).deleteByCreatedAtEpochMsLessThan(1234);
    }
}
