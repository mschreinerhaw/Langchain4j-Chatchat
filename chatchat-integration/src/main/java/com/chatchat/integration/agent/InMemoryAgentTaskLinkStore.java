package com.chatchat.integration.agent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/local fallback; production uses the JPA-backed store. */
final class InMemoryAgentTaskLinkStore implements AgentTaskLinkStore {
    private final Map<String, TaskLink> links = new ConcurrentHashMap<>();

    @Override public Optional<TaskLink> find(String executionId) {
        return Optional.ofNullable(links.get(executionId));
    }
    @Override public long count() { return links.size(); }
    @Override public void save(TaskLink link) { links.put(link.executionId(), link); }
    @Override public void delete(String executionId) { links.remove(executionId); }
    @Override public void deleteExpired(long cutoffEpochMs) {
        links.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMs() < cutoffEpochMs);
    }
}
