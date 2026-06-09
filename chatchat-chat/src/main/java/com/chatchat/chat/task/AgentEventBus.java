package com.chatchat.chat.task;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class AgentEventBus {

    private final AgentTaskProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, BlockingQueue<AgentEvent>> tenantQueues = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<AgentEvent>> resultQueues = new ConcurrentHashMap<>();

    public AgentEventBus(AgentTaskProperties properties, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    public void publish(AgentEvent event) {
        boolean accepted = queueForTenant(event.getTenantId()).offer(event);
        if (!accepted) {
            throw new IllegalStateException("Tenant agent queue is full: " + normalizeTenant(event.getTenantId()));
        }
        eventPublisher.publishEvent(event);
    }

    public AgentEvent poll(String tenantId, long timeout, TimeUnit unit) throws InterruptedException {
        return queueForTenant(tenantId).poll(timeout, unit);
    }

    public void publishResult(AgentEvent event) {
        BlockingQueue<AgentEvent> queue = resultQueues.computeIfAbsent(
            event.getTaskId(),
            ignored -> new LinkedBlockingQueue<>(properties.getQueueCapacity())
        );
        if (!queue.offer(event)) {
            throw new IllegalStateException("Agent result queue is full: " + event.getTaskId());
        }
        eventPublisher.publishEvent(event);
    }

    public AgentEvent pollResult(String taskId, long timeout, TimeUnit unit) throws InterruptedException {
        BlockingQueue<AgentEvent> queue = resultQueues.computeIfAbsent(
            taskId,
            ignored -> new LinkedBlockingQueue<>(properties.getQueueCapacity())
        );
        AgentEvent event = queue.poll(timeout, unit);
        if (queue.isEmpty() && event != null) {
            resultQueues.remove(taskId, queue);
        }
        return event;
    }

    private BlockingQueue<AgentEvent> queueForTenant(String tenantId) {
        return tenantQueues.computeIfAbsent(normalizeTenant(tenantId), ignored -> new LinkedBlockingQueue<>(properties.getQueueCapacity()));
    }

    private String normalizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        return tenantId.trim();
    }
}
