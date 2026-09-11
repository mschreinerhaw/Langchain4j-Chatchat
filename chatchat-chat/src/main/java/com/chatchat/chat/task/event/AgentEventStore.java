package com.chatchat.chat.task.event;

import java.util.List;
import java.util.Optional;

public interface AgentEventStore {

    /** Whether ordered append work may run off the Agent execution thread. */
    default boolean supportsDeferredAppend() {
        return false;
    }

    /**
     * Appends an event with the next stream sequence. Implementations backed by
     * a transactional database may override this to allocate the sequence under
     * the same stream lock used by {@link #save(AgentEvent)}.
     */
    default String append(AgentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Agent event is required");
        }
        if (event.getSequence() == null || event.getSequence() <= 0) {
            event.setSequence(nextSequence(event.getTenantId(), event.getSessionId(), event.getTaskId()));
        }
        return save(event);
    }

    /** Appends an ordered group while retaining every individual event. */
    default List<String> appendAll(List<AgentEvent> events) {
        if (events == null || events.isEmpty()) return List.of();
        return events.stream().map(this::append).toList();
    }

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
     * Lists task events whose sequence is greater than the caller's cursor.
     */
    default List<AgentEvent> listByTaskAfter(String tenantId, String sessionId, String taskId,
                                             long afterSequence, int limit) {
        return listByTask(tenantId, sessionId, taskId, Integer.MAX_VALUE).stream()
            .filter(event -> event.getSequence() != null && event.getSequence() > Math.max(0L, afterSequence))
            .limit(Math.max(1, limit))
            .toList();
    }

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
