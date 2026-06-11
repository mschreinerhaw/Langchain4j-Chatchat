package com.chatchat.chat.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@ConditionalOnProperty(prefix = "chatchat.agent.task.event-store", name = "type", havingValue = "memory")
public class InMemoryAgentEventStore implements AgentEventStore {

    private final Queue<AgentEvent> events = new ConcurrentLinkedQueue<>();

    /**
     * Saves the save.
     *
     * @param event the event value
     * @return the saved save
     */
    @Override
    public String save(AgentEvent event) {
        if (event.getCreateTime() <= 0) {
            event.setCreateTime(System.currentTimeMillis());
        }
        events.offer(event);
        return AgentEventKeyBuilder.build(event);
    }

    /**
     * Lists the by task.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @param limit the limit value
     * @return the by task list
     */
    @Override
    public List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit) {
        return events.stream()
            .filter(event -> same(tenantId, event.getTenantId()))
            .filter(event -> same(sessionId, event.getSessionId()))
            .filter(event -> taskId == null || taskId.equals(event.getTaskId()))
            .sorted(Comparator
                .comparing((AgentEvent event) -> event.getSequence() == null ? Long.MAX_VALUE : event.getSequence())
                .thenComparingLong(AgentEvent::getCreateTime))
            .limit(Math.max(1, limit))
            .toList();
    }

    /**
     * Returns whether same.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @return whether the condition is satisfied
     */
    private boolean same(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) {
            return false;
        }
        return expected.trim().equals(actual.trim());
    }
}
