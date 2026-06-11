package com.chatchat.chat.task;

import java.util.List;
import java.util.Optional;

public interface AgentEventStore {

    /**
     * Saves the save.
     *
     * @param event the event value
     * @return the saved save
     */
    String save(AgentEvent event);

    /**
     * Lists the by task.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @param limit the limit value
     * @return the by task list
     */
    List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit);

    /**
     * Finds the first by task and type.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @param type the type value
     * @return the matching first by task and type
     */
    default Optional<AgentEvent> findFirstByTaskAndType(String tenantId, String sessionId, String taskId, String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return listByTask(tenantId, sessionId, taskId, Integer.MAX_VALUE).stream()
            .filter(event -> type.equalsIgnoreCase(event.getType()))
            .findFirst();
    }

    /**
     * Finds the latest by task.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @return the matching latest by task
     */
    default Optional<AgentEvent> findLatestByTask(String tenantId, String sessionId, String taskId) {
        return listByTask(tenantId, sessionId, taskId, Integer.MAX_VALUE).stream()
            .reduce((previous, current) -> current);
    }

    /**
     * Performs the next sequence operation.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @return the operation result
     */
    default long nextSequence(String tenantId, String sessionId, String taskId) {
        return findLatestByTask(tenantId, sessionId, taskId)
            .map(AgentEvent::getSequence)
            .filter(sequence -> sequence != null && sequence > 0)
            .orElse(0L) + 1L;
    }
}
