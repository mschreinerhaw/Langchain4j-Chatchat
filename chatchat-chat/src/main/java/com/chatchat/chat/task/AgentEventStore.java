package com.chatchat.chat.task;

import java.util.List;
import java.util.Optional;

public interface AgentEventStore {

    String save(AgentEvent event);

    List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit);

    default Optional<AgentEvent> findFirstByTaskAndType(String tenantId, String sessionId, String taskId, String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return listByTask(tenantId, sessionId, taskId, Integer.MAX_VALUE).stream()
            .filter(event -> type.equalsIgnoreCase(event.getType()))
            .findFirst();
    }

    default Optional<AgentEvent> findLatestByTask(String tenantId, String sessionId, String taskId) {
        return listByTask(tenantId, sessionId, taskId, Integer.MAX_VALUE).stream()
            .reduce((previous, current) -> current);
    }

    default long nextSequence(String tenantId, String sessionId, String taskId) {
        return findLatestByTask(tenantId, sessionId, taskId)
            .map(AgentEvent::getSequence)
            .filter(sequence -> sequence != null && sequence > 0)
            .orElse(0L) + 1L;
    }
}
