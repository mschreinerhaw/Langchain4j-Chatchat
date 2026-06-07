package com.chatchat.api.agent.task;

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

    @Override
    public String save(AgentEvent event) {
        if (event.getCreateTime() <= 0) {
            event.setCreateTime(System.currentTimeMillis());
        }
        events.offer(event);
        return AgentEventKeyBuilder.build(event);
    }

    @Override
    public List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit) {
        return events.stream()
            .filter(event -> same(tenantId, event.getTenantId()))
            .filter(event -> same(sessionId, event.getSessionId()))
            .filter(event -> taskId == null || taskId.equals(event.getTaskId()))
            .sorted(Comparator.comparingLong(AgentEvent::getCreateTime))
            .limit(Math.max(1, limit))
            .toList();
    }

    private boolean same(String expected, String actual) {
        String left = expected == null || expected.isBlank() ? "default" : expected;
        String right = actual == null || actual.isBlank() ? "default" : actual;
        return left.equals(right);
    }
}
