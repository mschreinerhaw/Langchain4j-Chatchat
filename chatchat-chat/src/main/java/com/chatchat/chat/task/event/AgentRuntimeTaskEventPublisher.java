package com.chatchat.chat.task.event;

import com.chatchat.chat.task.core.AgentTaskService;

import com.chatchat.chat.task.core.AgentTaskLatestRepository;

import com.chatchat.chat.task.core.AgentTaskLatestEntity;

import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.event.AgentRunEventPublisher;
import com.chatchat.agents.runtime.event.AgentRunEventType;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class AgentRuntimeTaskEventPublisher implements AgentRunEventPublisher {

    private static final int MAX_PERSISTED_RUNTIME_PAYLOAD_CHARS = 64_000;
    private static final int MAX_ACTIVE_RUN_CACHE_ENTRIES = 4_096;
    // Runtime traces remain fully durable, but a high-latency database should not turn
    // dozens of audit events into dozens of blocking round trips before final synthesis.
    private static final int DEFERRED_APPEND_BATCH_SIZE = 32;

    private final AgentTaskLatestRepository latestRepository;
    private final AgentEventStore eventStore;
    private final AgentEventBus eventBus;
    private final ObjectMapper objectMapper;
    /**
     * Runtime events for one run share immutable task identity and parent-question data.
     * Keep those values locally while the run is active so audit persistence does not
     * pay two remote reads for every event.
     */
    private final Map<String, AgentTaskLatestEntity> taskByRunId = boundedCache();
    private final Map<String, Optional<String>> parentQuestionIdByTask = boundedCache();
    private final Map<String, CompletableFuture<Void>> appendTailByRunId = new ConcurrentHashMap<>();
    private final Map<String, List<PendingEvent>> pendingByRunId = new ConcurrentHashMap<>();
    private final ExecutorService appendExecutor = Executors.newFixedThreadPool(4, deferredAppendThreadFactory());

    @Override
    public void publish(AgentRunEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        Optional<AgentTaskLatestEntity> task = cachedTask(event.runId());
        if (task.isEmpty()) {
            log.debug("Agent runtime event has no matching async task. runId={} eventType={}",
                event.runId(), event.type());
            return;
        }
        AgentTaskLatestEntity latest = task.get();
        AgentEvent taskEvent = AgentEvent.builder()
            .eventId(event.eventId())
            .taskId(latest.getTaskId())
            .runId(event.runId())
            .executionId(latest.getExecutionId())
            .attemptId(latest.getExecutionAttemptId())
            .eventScope("RUNTIME")
            .tenantId(latest.getTenantId())
            .userId(latest.getUserId())
            .agentId(latest.getAgentId())
            .sessionId(latest.getSessionId())
            .parentEventId(parentQuestionEventId(latest))
            .toolName(toolName(event))
            .type(taskEventType(event.type()))
            .status(taskStatus(event.type()))
            .payload(writePayload(event))
            .errorCode(errorCode(event))
            .createTime(event.createdAt())
            .build();
        if (eventStore.supportsDeferredAppend()) {
            List<PendingEvent> batch = bufferAndDrain(event.runId(),
                new PendingEvent(taskEvent, latest, event), isTerminal(event.type()));
            CompletableFuture<Void> tail = batch.isEmpty()
                ? appendTailByRunId.getOrDefault(event.runId(), CompletableFuture.completedFuture(null))
                : enqueueBatch(event.runId(), batch);
            if (isTerminal(event.type())) {
                try {
                    tail.join();
                } catch (CompletionException failure) {
                    throw failure.getCause() instanceof RuntimeException runtime
                        ? runtime : failure;
                } finally {
                    appendTailByRunId.remove(event.runId(), tail);
                    pendingByRunId.remove(event.runId());
                    evictRun(event.runId(), latest);
                }
            }
            return;
        }
        persistAndPublish(taskEvent, latest, event);
        if (isTerminal(event.type())) {
            evictRun(event.runId(), latest);
        }
    }

    private void persistAndPublish(AgentEvent taskEvent, AgentTaskLatestEntity latest,
                                   AgentRunEvent event) {
        eventStore.append(taskEvent);
        publishPersisted(taskEvent, latest, event);
    }

    private void persistBatch(List<PendingEvent> batch) {
        eventStore.appendAll(batch.stream().map(PendingEvent::taskEvent).toList());
        batch.forEach(pending -> publishPersisted(
            pending.taskEvent(), pending.latest(), pending.runtimeEvent()));
    }

    private void publishPersisted(AgentEvent taskEvent, AgentTaskLatestEntity latest,
                                  AgentRunEvent event) {
        eventBus.publishResult(taskEvent);
        if (event.type() == AgentRunEventType.BUSINESS_TEMPLATE_REQUIREMENT_MATCHING) {
            log.info("Business template selection bridged to task flow. taskId={} candidateCount={} "
                    + "selectedCount={} selectedTemplateIds={} rejectedTemplateIds={} fallbackUsed={}",
                latest.getTaskId(),
                event.payload().get("candidateCount"),
                event.payload().get("selectedCount"),
                event.payload().get("selectedTemplateIds"),
                event.payload().get("rejectedTemplateIds"),
                event.payload().get("fallbackUsed"));
        }
        log.info("Agent runtime event bridged to task flow. taskId={} runId={} runtimeEventType={} taskEventType={} status={} message={}",
            latest.getTaskId(),
            event.runId(),
            event.type(),
            taskEvent.getType(),
            taskEvent.getStatus(),
            event.message());
    }

    private List<PendingEvent> bufferAndDrain(String runId, PendingEvent event, boolean force) {
        List<PendingEvent> buffer = pendingByRunId.computeIfAbsent(
            runId, ignored -> new ArrayList<>(DEFERRED_APPEND_BATCH_SIZE));
        synchronized (buffer) {
            buffer.add(event);
            if (!force && buffer.size() < DEFERRED_APPEND_BATCH_SIZE) return List.of();
            List<PendingEvent> drained = List.copyOf(buffer);
            buffer.clear();
            return drained;
        }
    }

    private CompletableFuture<Void> enqueueBatch(String runId, List<PendingEvent> batch) {
        return appendTailByRunId.compute(runId, (ignored, previous) ->
            (previous == null ? CompletableFuture.completedFuture(null) : previous)
                .thenRunAsync(() -> persistBatch(batch), appendExecutor));
    }

    private Optional<AgentTaskLatestEntity> cachedTask(String runId) {
        AgentTaskLatestEntity cached = taskByRunId.get(runId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<AgentTaskLatestEntity> loaded = latestRepository.findById(runId)
            .or(() -> latestRepository.findByExecutionAttemptId(runId));
        loaded.ifPresent(task -> taskByRunId.put(runId, task));
        return loaded;
    }

    private String parentQuestionEventId(AgentTaskLatestEntity latest) {
        String cacheKey = taskCacheKey(latest);
        return parentQuestionIdByTask.computeIfAbsent(cacheKey, ignored -> eventStore.findFirstByTaskAndType(
                latest.getTenantId(),
                latest.getSessionId(),
                latest.getTaskId(),
                "QUESTION"
            )
            .map(AgentEvent::getEventId))
            .orElse(null);
    }

    private void evictRun(String runId, AgentTaskLatestEntity latest) {
        taskByRunId.remove(runId);
        parentQuestionIdByTask.remove(taskCacheKey(latest));
    }

    private boolean isTerminal(AgentRunEventType type) {
        return type == AgentRunEventType.RUN_COMPLETED
            || type == AgentRunEventType.RUN_CANCELLED
            || type == AgentRunEventType.RUN_FAILED;
    }

    private String taskCacheKey(AgentTaskLatestEntity latest) {
        return String.join("\u001f",
            safeCachePart(latest.getTenantId()),
            safeCachePart(latest.getSessionId()),
            safeCachePart(latest.getTaskId()));
    }

    private String safeCachePart(String value) {
        return value == null ? "" : value;
    }

    private static <K, V> Map<K, V> boundedCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_ACTIVE_RUN_CACHE_ENTRIES;
            }
        });
    }

    private static ThreadFactory deferredAppendThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task,
                "agent-runtime-event-append-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void flushDeferredAppends() {
        pendingByRunId.forEach((runId, buffer) -> {
            List<PendingEvent> drained;
            synchronized (buffer) {
                drained = List.copyOf(buffer);
                buffer.clear();
            }
            if (!drained.isEmpty()) enqueueBatch(runId, drained);
        });
        CompletableFuture<?>[] pending = appendTailByRunId.values()
            .toArray(CompletableFuture[]::new);
        try {
            if (pending.length > 0) {
                CompletableFuture.allOf(pending).get(30, TimeUnit.SECONDS);
            }
        } catch (Exception failure) {
            log.warn("Timed out while flushing deferred Agent runtime audit events during shutdown", failure);
        } finally {
            appendExecutor.shutdown();
            try {
                if (!appendExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    appendExecutor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                appendExecutor.shutdownNow();
            }
        }
    }

    private record PendingEvent(AgentEvent taskEvent, AgentTaskLatestEntity latest,
                                AgentRunEvent runtimeEvent) {
    }

    private String taskEventType(AgentRunEventType type) {
        if (type == null) {
            return "RUNTIME_EVENT";
        }
        return switch (type) {
            case RUN_SUBMITTED -> "RUNTIME_SUBMITTED";
            case RUN_STARTED -> "RUNTIME_STARTED";
            case STEP_RECORDED -> "RUNTIME_STEP";
            case OBSERVATION_RECORDED -> "RUNTIME_OBSERVATION";
            case BUSINESS_TEMPLATE_REQUIREMENT_MATCHING -> "BUSINESS_TEMPLATE_REQUIREMENT_MATCHING";
            case CONFIRMATION_REQUIRED -> "RUNTIME_CONFIRMATION";
            case RUN_COMPLETED -> "RUNTIME_COMPLETED";
            case RUN_CANCELLED -> "RUNTIME_CANCELLED";
            case RUN_FAILED -> "RUNTIME_FAILED";
        };
    }

    private String taskStatus(AgentRunEventType type) {
        if (type == null) {
            return "RUNNING";
        }
        return switch (type) {
            case RUN_SUBMITTED -> "PENDING";
            case CONFIRMATION_REQUIRED -> "WAIT_CONFIRMATION";
            // Runtime completion precedes task result adaptation and publication.
            // Keep the task active until AgentTaskService publishes the final ANSWER/RESULT.
            case RUN_COMPLETED -> "RUNNING";
            case RUN_CANCELLED -> "CANCELLED";
            case RUN_FAILED -> "FAILED";
            default -> "RUNNING";
        };
    }

    private String toolName(AgentRunEvent event) {
        Object direct = event.payload().get("toolName");
        if (direct == null) {
            direct = event.payload().get("resolvedToolName");
        }
        if (direct == null) {
            direct = event.payload().get("source");
        }
        return direct == null || String.valueOf(direct).isBlank() ? null : String.valueOf(direct).trim();
    }

    private String errorCode(AgentRunEvent event) {
        if (event.type() == AgentRunEventType.RUN_FAILED) {
            Object code = event.payload() == null ? null : event.payload().get("errorCode");
            return code == null || String.valueOf(code).isBlank()
                ? "AGENT_RUNTIME_FAILED"
                : String.valueOf(code);
        }
        if (event.type() == AgentRunEventType.RUN_CANCELLED) {
            return "AGENT_RUNTIME_CANCELLED";
        }
        return null;
    }

    private String writePayload(AgentRunEvent event) {
        Map<String, Object> runtimePayload = event.payload() == null
            ? Map.of()
            : new LinkedHashMap<>(event.payload());
        if (event.type() == AgentRunEventType.RUN_COMPLETED) {
            // The task layer still has to compile its final result contract. Do not expose
            // a Runtime draft as user-facing content before ANSWER/RESULT is published.
            runtimePayload.remove("answer");
            runtimePayload.remove("uiResponse");
            runtimePayload.remove("executionResult");
        }
        runtimePayload = compactRuntimePayload(event, runtimePayload);
        String status = taskStatus(event.type());
        String answer = terminalAnswer(event, runtimePayload);
        Map<String, Object> uiResponse = terminalUiResponse(status, answer, runtimePayload);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", displayMessage(event, answer));
        payload.put("runId", event.runId());
        payload.put("runtimeEventId", event.eventId());
        payload.put("runtimeEventType", event.type() == null ? null : event.type().name());
        payload.put("createdAt", event.createdAt());
        payload.put("payload", runtimePayload);
        if (answer != null && !answer.isBlank()) {
            payload.put("contractVersion", "ui_response_v2");
            payload.put("status", status);
            payload.put("answer", answer);
            payload.put("uiResponse", uiResponse);
            payload.put("executionResult", executionResult(status, answer, uiResponse, runtimePayload));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize agent runtime task event payload", ex);
        }
    }

    private Map<String, Object> compactRuntimePayload(AgentRunEvent event,
                                                       Map<String, Object> runtimePayload) {
        if (runtimePayload.isEmpty()
            || (event.type() != AgentRunEventType.STEP_RECORDED
                && event.type() != AgentRunEventType.OBSERVATION_RECORDED
                && event.type() != AgentRunEventType.RUN_COMPLETED)) {
            return runtimePayload;
        }
        Object summarized = ToolLogSummarizer.summarize(
            runtimePayload,
            MAX_PERSISTED_RUNTIME_PAYLOAD_CHARS
        );
        if (!(summarized instanceof Map<?, ?> summarizedMap)) {
            return Map.of(
                "contentPreview", String.valueOf(summarized),
                "eventPayloadCompacted", true
            );
        }
        Map<String, Object> compacted = new LinkedHashMap<>();
        summarizedMap.forEach((key, value) -> {
            if (key != null) {
                compacted.put(String.valueOf(key), value);
            }
        });
        compacted.put("eventPayloadCompacted", true);
        return compacted;
    }

    private String displayMessage(AgentRunEvent event, String answer) {
        if (event != null && event.type() == AgentRunEventType.RUN_FAILED && answer != null && !answer.isBlank()) {
            return answer;
        }
        return event == null ? "" : event.message();
    }

    private String terminalAnswer(AgentRunEvent event, Map<String, Object> payload) {
        if (event == null || (event.type() != AgentRunEventType.RUN_FAILED && event.type() != AgentRunEventType.RUN_CANCELLED)) {
            return null;
        }
        Map<String, Object> uiResponse = asMap(payload.get("uiResponse"));
        String direct = firstText(
            stringValue(uiResponse.get("answer")),
            stringValue(payload.get("answer")),
            stringValue(payload.get("errorMessage")),
            stringValue(payload.get("message"))
        );
        if (direct != null && !direct.isBlank() && !"Agent run failed".equalsIgnoreCase(direct)) {
            return direct;
        }
        String code = stringValue(payload.get("errorCode"));
        String fallback = firstText(stringValue(event.message()), "运行时未返回具体失败原因");
        return "本次执行失败，运行时已记录失败信息。"
            + "\n\n失败类型：" + firstText(code, "AGENT_RUNTIME_FAILED")
            + "\n失败原因：" + fallback
            + "\n\n该失败会作为观察结果返回给用户端，请根据工具执行日志、权限或参数配置继续排查。";
    }

    private Map<String, Object> terminalUiResponse(String status, String answer, Map<String, Object> payload) {
        Map<String, Object> existing = asMap(payload.get("uiResponse"));
        if (!existing.isEmpty()) {
            return existing;
        }
        if (answer == null || answer.isBlank()) {
            return Map.of();
        }
        Map<String, Object> uiResponse = new LinkedHashMap<>();
        uiResponse.put("contractVersion", "ui_response_v2");
        uiResponse.put("status", firstText(status, "FAILED"));
        uiResponse.put("answer", answer);
        uiResponse.put("citations", List.of());
        uiResponse.put("evidencePremises", List.of());
        uiResponse.put("confidence", null);
        uiResponse.put("evidenceSummary", "");
        uiResponse.put("visualization", Map.of("type", "none"));
        return uiResponse;
    }

    private Map<String, Object> executionResult(String status,
                                                String answer,
                                                Map<String, Object> uiResponse,
                                                Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", firstText(status, "FAILED"));
        result.put("uiResponse", uiResponse);
        result.put("semanticFlags", Map.of(
            "hasAnswer", answer != null && !answer.isBlank(),
            "hasInsight", false,
            "hasToolOutput", booleanValue(payload.get("toolTraceCount")) || !listValue(payload.get("observations")).isEmpty(),
            "hasSources", false,
            "hasArtifact", true
        ));
        result.put("message", answer);
        result.put("traceSummary", Map.of(
            "sourceCount", 0,
            "toolTraceCount", intValue(payload.get("toolTraceCount"))
        ));
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("errorCode", nonNullText(payload.get("errorCode")));
        debug.put("errorMessage", nonNullText(payload.get("errorMessage")));
        result.put("debug", debug);
        return result;
    }

    private String nonNullText(Object value) {
        String text = stringValue(value);
        return text == null ? "" : text.trim();
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0;
        }
        return value instanceof Boolean bool && bool;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
