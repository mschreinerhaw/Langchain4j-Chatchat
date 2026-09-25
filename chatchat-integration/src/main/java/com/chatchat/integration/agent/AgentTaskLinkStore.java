package com.chatchat.integration.agent;

import java.util.Optional;

/** Runtime-owned link between a local execution and a resumable remote A2A task. */
public interface AgentTaskLinkStore {
    Optional<TaskLink> find(String executionId);
    long count();
    void save(TaskLink link);
    void delete(String executionId);
    void deleteExpired(long cutoffEpochMs);

    record TaskLink(String executionId, String tenantId, String agentId,
                    String taskId, String contextId, long createdAtEpochMs) {
        public TaskLink {
            if (blank(executionId) || blank(tenantId) || blank(agentId) || blank(taskId))
                throw new IllegalArgumentException("A2A task link requires execution, tenant, agent and task IDs");
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}
