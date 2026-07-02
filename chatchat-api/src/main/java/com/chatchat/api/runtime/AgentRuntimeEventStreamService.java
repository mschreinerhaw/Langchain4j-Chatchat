package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRuntime;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Service
@RequiredArgsConstructor
public class AgentRuntimeEventStreamService {

    private static final long MAX_TIMEOUT_MS = 1_800_000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 1_000L;

    private final AgentRuntime agentRuntime;
    private final ExecutorService executor = Executors.newCachedThreadPool(new StreamThreadFactory());

    public SseEmitter streamEvents(String runId,
                                   long afterCreatedAt,
                                   int limit,
                                   long pollIntervalMs,
                                   long timeoutMs) {
        long safeTimeoutMs = timeoutMs <= 0 ? 0L : Math.min(timeoutMs, MAX_TIMEOUT_MS);
        SseEmitter emitter = new SseEmitter(safeTimeoutMs <= 0 ? 0L : safeTimeoutMs + 5_000L);
        executor.execute(() -> streamLoop(
            emitter,
            runId,
            Math.max(0L, afterCreatedAt),
            normalizeLimit(limit),
            normalizePollInterval(pollIntervalMs),
            safeTimeoutMs
        ));
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void streamLoop(SseEmitter emitter,
                            String runId,
                            long cursor,
                            int limit,
                            long pollIntervalMs,
                            long timeoutMs) {
        boolean hasDeadline = timeoutMs > 0;
        long deadlineAt = hasDeadline ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        try {
            AgentRun initial = agentRuntime.find(runId).orElse(null);
            if (initial == null) {
                send(emitter, "error", Map.of("message", "Agent run not found: " + runId));
                emitter.complete();
                return;
            }
            send(emitter, "start", Map.of(
                "runId", runId,
                "status", initial.status().name(),
                "cursor", cursor,
                "timestamp", System.currentTimeMillis()
            ));
            while (!hasDeadline || System.currentTimeMillis() <= deadlineAt) {
                List<AgentRunEvent> events = agentRuntime.events(runId, cursor, limit);
                for (AgentRunEvent event : events) {
                    send(emitter, "event", event);
                    cursor = Math.max(cursor, event.createdAt());
                }
                AgentRun current = agentRuntime.find(runId).orElse(null);
                if (current == null) {
                    send(emitter, "error", Map.of("message", "Agent run not found: " + runId));
                    emitter.complete();
                    return;
                }
                if (isTerminal(current.status())) {
                    send(emitter, "done", Map.of(
                        "runId", runId,
                        "status", current.status().name(),
                        "cursor", cursor,
                        "timestamp", System.currentTimeMillis()
                    ));
                    emitter.complete();
                    return;
                }
                if (events.isEmpty()) {
                    send(emitter, "heartbeat", Map.of(
                        "runId", runId,
                        "status", current.status().name(),
                        "cursor", cursor,
                        "timestamp", System.currentTimeMillis()
                    ));
                }
                sleep(pollIntervalMs);
            }
            if (hasDeadline) {
                send(emitter, "timeout", Map.of("runId", runId, "cursor", cursor, "timestamp", System.currentTimeMillis()));
                emitter.complete();
            }
        } catch (Exception ex) {
            sendError(emitter, ex);
        }
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
            || status == AgentRunStatus.FAILED
            || status == AgentRunStatus.CANCELLED;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private long normalizePollInterval(long pollIntervalMs) {
        if (pollIntervalMs <= 0) {
            return DEFAULT_POLL_INTERVAL_MS;
        }
        return Math.min(Math.max(pollIntervalMs, 100L), 10_000L);
    }

    private void sleep(long pollIntervalMs) {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent runtime event stream interrupted", ex);
        }
    }

    private void send(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private void sendError(SseEmitter emitter, Exception ex) {
        try {
            send(emitter, "error", Map.of("message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        } catch (IOException ignored) {
            // The client may already be gone.
        } finally {
            emitter.completeWithError(ex);
        }
    }

    private static final class StreamThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-runtime-event-stream");
            thread.setDaemon(true);
            return thread;
        }
    }
}
