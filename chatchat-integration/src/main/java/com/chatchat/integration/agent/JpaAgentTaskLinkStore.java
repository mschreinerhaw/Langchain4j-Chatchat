package com.chatchat.integration.agent;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Durable A2A task identity with expiry; credentials and evidence are never persisted here. */
@Component
public class JpaAgentTaskLinkStore implements AgentTaskLinkStore {
    private final AgentTaskLinkRepository repository;

    public JpaAgentTaskLinkStore(AgentTaskLinkRepository repository) {
        this.repository = repository;
    }

    @Override public Optional<TaskLink> find(String executionId) {
        return repository.findById(executionId).map(AgentTaskLinkEntity::toLink);
    }
    @Override public long count() { return repository.count(); }
    @Override public void save(TaskLink link) { repository.saveAndFlush(new AgentTaskLinkEntity(link)); }
    @Override public void delete(String executionId) { repository.deleteById(executionId); }
    @Override @Transactional public void deleteExpired(long cutoffEpochMs) {
        repository.deleteByCreatedAtEpochMsLessThan(cutoffEpochMs);
    }
}
