package com.chatchat.chat.task.event;

import com.chatchat.chat.task.core.AgentTaskService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes durable confirmation commands without occupying a tenant task worker.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentConfirmationEventConsumer {

    private static final int MAX_DELIVERY_ATTEMPTS = 3;

    private final AgentEventBus eventBus;
    private final AgentTaskService taskService;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::consumeLoop, "agent-confirmation-event-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                AgentEvent event = eventBus.pollConfirmation(1, TimeUnit.SECONDS);
                if (event != null) {
                    consume(event);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException ex) {
                log.error("Confirmation event consumer failed", ex);
            }
        }
    }

    void consume(AgentEvent event) {
        try {
            taskService.consumeConfirmationEvent(event);
        } catch (RuntimeException ex) {
            int attempts = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
            event.setRetryCount(attempts);
            if (running.get() && attempts < MAX_DELIVERY_ATTEMPTS) {
                eventBus.publishConfirmation(event);
                log.warn("Confirmation event requeued: taskId={} attempt={} error={}",
                    event.getTaskId(), attempts, ex.getMessage());
                return;
            }
            log.error("Confirmation event processing failed: taskId={} attempts={}",
                event.getTaskId(), attempts, ex);
        }
    }
}
