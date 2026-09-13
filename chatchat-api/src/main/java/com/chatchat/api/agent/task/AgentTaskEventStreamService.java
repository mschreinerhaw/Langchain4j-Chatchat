package com.chatchat.api.agent.task;

import com.chatchat.chat.task.core.AgentTaskResponse;
import com.chatchat.chat.task.core.AgentTaskService;
import com.chatchat.chat.task.event.AgentEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Incremental, sequence-based event stream for the chat task UI. */
@Service
@RequiredArgsConstructor
public class AgentTaskEventStreamService {

    private static final long MAX_TIMEOUT_MS = 1_800_000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 250L;
    private static final long HEARTBEAT_INTERVAL_MS = 2_000L;
    private static final Set<String> TERMINAL_STATUSES = Set.of(
        "SUCCESS", "FAILED", "CANCELLED", "KILLED", "REJECTED", "TIMEOUT_CANCELLED",
        "PARTIAL", "PARTIAL_SUCCESS", "EMPTY",
        "TIME_BUDGET_EXHAUSTED", "MODEL_BUDGET_EXHAUSTED", "NO_PRESENTABLE_RESULT");

    private final AgentTaskService taskService;
    private final ExecutorService executor = Executors.newCachedThreadPool(new StreamThreadFactory());

    public SseEmitter stream(String tenantId, String taskId, long afterSequence,
                             int limit, long pollIntervalMs, long timeoutMs) {
        long safeTimeout = timeoutMs <= 0 ? MAX_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
        SseEmitter emitter = new SseEmitter(safeTimeout + 5_000L);
        executor.execute(() -> streamLoop(emitter, tenantId, taskId,
            Math.max(0L, afterSequence), normalizeLimit(limit),
            normalizePollInterval(pollIntervalMs), safeTimeout));
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void streamLoop(SseEmitter emitter, String tenantId, String taskId,
                            long cursor, int limit, long pollIntervalMs, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextHeartbeat = System.currentTimeMillis();
        try {
            AgentTaskResponse initial = taskService.get(tenantId, taskId).orElseThrow();
            send(emitter, "start", Map.of(
                "taskId", taskId, "status", initial.status(), "cursor", cursor));
            while (System.currentTimeMillis() <= deadline) {
                List<AgentEvent> events = taskService.listEventsAfter(tenantId, taskId, cursor, limit);
                for (AgentEvent event : events) {
                    send(emitter, "event", event);
                    if (event.getSequence() != null) cursor = Math.max(cursor, event.getSequence());
                }
                AgentTaskResponse current = taskService.get(tenantId, taskId).orElseThrow();
                AgentEvent terminal = events.stream().filter(this::terminalEvent)
                    .reduce((previous, value) -> value).orElse(null);
                if (terminal != null || terminalStatus(current.status())) {
                    send(emitter, "done", Map.of(
                        "taskId", taskId,
                        "status", current.status() == null ? "" : current.status(),
                        "cursor", cursor));
                    emitter.complete();
                    return;
                }
                long now = System.currentTimeMillis();
                if (events.isEmpty() && now >= nextHeartbeat) {
                    send(emitter, "heartbeat", Map.of(
                        "taskId", taskId,
                        "status", current.status() == null ? "" : current.status(),
                        "cursor", cursor,
                        "timestamp", now));
                    nextHeartbeat = now + HEARTBEAT_INTERVAL_MS;
                }
                sleep(pollIntervalMs);
            }
            send(emitter, "timeout", Map.of("taskId", taskId, "cursor", cursor));
            emitter.complete();
        } catch (Exception ex) {
            try {
                send(emitter, "error", Map.of("message",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            } catch (IOException ignored) {
                // Client disconnected.
            } finally {
                emitter.completeWithError(ex);
            }
        }
    }

    private boolean terminalEvent(AgentEvent event) {
        if (event == null) return false;
        String type = normalized(event.getType());
        return "ANSWER".equals(type) || "RESULT".equals(type) || "ERROR".equals(type)
            || "NEEDS_CONFIRMATION".equals(type) || "COMPLETE".equals(type)
            || terminalStatus(event.getStatus());
    }

    private boolean terminalStatus(String status) {
        return TERMINAL_STATUSES.contains(normalized(status))
            || "WAIT_CONFIRMATION".equals(normalized(status))
            || "WAITING_CONFIRM".equals(normalized(status));
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? 100 : Math.min(limit, 500);
    }

    private long normalizePollInterval(long value) {
        return value <= 0 ? DEFAULT_POLL_INTERVAL_MS : Math.min(Math.max(value, 100L), 2_000L);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent task event stream interrupted", ex);
        }
    }

    private void send(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private static final class StreamThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-task-event-stream");
            thread.setDaemon(true);
            return thread;
        }
    }
}
