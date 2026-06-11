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
    private final Map<String, BlockingQueue<AgentEvent>> confirmationQueues = new ConcurrentHashMap<>();

    /**
     * Creates a new AgentEventBus instance.
     *
     * @param properties the properties value
     * @param eventPublisher the event publisher value
     */
    public AgentEventBus(AgentTaskProperties properties, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes the publish.
     *
     * @param event the event value
     */
    public void publish(AgentEvent event) {
        boolean accepted = queueForTenant(event.getTenantId()).offer(event);
        if (!accepted) {
            throw new IllegalStateException("Tenant agent queue is full: " + normalizeTenant(event.getTenantId()));
        }
        eventPublisher.publishEvent(event);
    }

    /**
     * Performs the poll operation.
     *
     * @param tenantId the tenant id value
     * @param timeout the timeout value
     * @param unit the unit value
     * @return the operation result
     * @throws InterruptedException if the operation fails
     */
    public AgentEvent poll(String tenantId, long timeout, TimeUnit unit) throws InterruptedException {
        return queueForTenant(tenantId).poll(timeout, unit);
    }

    /**
     * Publishes the result.
     *
     * @param event the event value
     */
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

    /**
     * Performs the poll result operation.
     *
     * @param taskId the task id value
     * @param timeout the timeout value
     * @param unit the unit value
     * @return the operation result
     * @throws InterruptedException if the operation fails
     */
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

    /**
     * Publishes the confirmation.
     *
     * @param event the event value
     */
    public void publishConfirmation(AgentEvent event) {
        BlockingQueue<AgentEvent> queue = confirmationQueues.computeIfAbsent(
            event.getTaskId(),
            ignored -> new LinkedBlockingQueue<>(properties.getQueueCapacity())
        );
        if (!queue.offer(event)) {
            throw new IllegalStateException("Agent confirmation queue is full: " + event.getTaskId());
        }
        eventPublisher.publishEvent(event);
    }

    /**
     * Performs the poll confirmation operation.
     *
     * @param taskId the task id value
     * @param timeout the timeout value
     * @param unit the unit value
     * @return the operation result
     * @throws InterruptedException if the operation fails
     */
    public AgentEvent pollConfirmation(String taskId, long timeout, TimeUnit unit) throws InterruptedException {
        BlockingQueue<AgentEvent> queue = confirmationQueues.computeIfAbsent(
            taskId,
            ignored -> new LinkedBlockingQueue<>(properties.getQueueCapacity())
        );
        AgentEvent event = queue.poll(timeout, unit);
        if (queue.isEmpty() && event != null) {
            confirmationQueues.remove(taskId, queue);
        }
        return event;
    }

    /**
     * Performs the clear results operation.
     *
     * @param taskId the task id value
     */
    public void clearResults(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            resultQueues.remove(taskId);
        }
    }

    /**
     * Performs the clear confirmations operation.
     *
     * @param taskId the task id value
     */
    public void clearConfirmations(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            confirmationQueues.remove(taskId);
        }
    }

    /**
     * Performs the queue for tenant operation.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    private BlockingQueue<AgentEvent> queueForTenant(String tenantId) {
        return tenantQueues.computeIfAbsent(normalizeTenant(tenantId), ignored -> new LinkedBlockingQueue<>(properties.getQueueCapacity()));
    }

    /**
     * Normalizes the tenant.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    private String normalizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        return tenantId.trim();
    }
}
