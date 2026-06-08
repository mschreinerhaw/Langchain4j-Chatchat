package com.chatchat.chat.task;

import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskService {

    private final AgentEventBus eventBus;
    private final AgentEventStore eventStore;
    private final AgentTaskLatestRepository latestRepository;
    private final InteractionOrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;
    private final AgentTaskProperties properties;

    @Qualifier("agentTaskExecutor")
    private final ThreadPoolTaskExecutor taskExecutor;

    private final Map<String, AtomicBoolean> workerStates = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public AgentTaskResponse submit(AgentTaskSubmitRequest request) {
        validate(request);
        AgentTaskSubmitRequest normalized = normalize(request);
        String taskId = UUID.randomUUID().toString();
        AgentTaskLatestEntity latest = new AgentTaskLatestEntity();
        latest.setTaskId(taskId);
        latest.setTenantId(normalized.getTenantId());
        latest.setUserId(normalized.getUserId());
        latest.setAgentId(normalized.getAgentId());
        latest.setSessionId(normalized.getSessionId());
        latest.setStatus("PENDING");
        latest.setQuestion(normalized.getQuery());
        latest.setCreateTime(Instant.now());
        latest.setUpdateTime(Instant.now());
        latestRepository.save(latest);

        AgentEvent question = AgentEvent.builder()
            .taskId(taskId)
            .tenantId(normalized.getTenantId())
            .userId(normalized.getUserId())
            .agentId(normalized.getAgentId())
            .sessionId(normalized.getSessionId())
            .type("QUESTION")
            .status("PENDING")
            .payload(writePayload(new AgentTaskPayload(normalized)))
            .build();
        eventStore.save(question);
        startWorker(normalized.getTenantId());
        eventBus.publish(question);
        return AgentTaskResponse.from(latest);
    }

    public Optional<AgentTaskResponse> get(String taskId) {
        return latestRepository.findById(taskId).map(AgentTaskResponse::from);
    }

    public List<AgentTaskResponse> list(String tenantId, String sessionId, int page, int pageSize) {
        int normalizedPage = Math.max(0, page - 1);
        int normalizedSize = Math.max(1, Math.min(100, pageSize));
        PageRequest pageable = PageRequest.of(normalizedPage, normalizedSize);
        if (tenantId != null && !tenantId.isBlank() && sessionId != null && !sessionId.isBlank()) {
            return latestRepository.findByTenantIdAndSessionIdOrderByCreateTimeDesc(tenantId.trim(), sessionId.trim(), pageable)
                .stream().map(AgentTaskResponse::from).toList();
        }
        if (tenantId != null && !tenantId.isBlank()) {
            return latestRepository.findByTenantIdOrderByCreateTimeDesc(tenantId.trim(), pageable)
                .stream().map(AgentTaskResponse::from).toList();
        }
        return latestRepository.findAllByOrderByCreateTimeDesc(pageable).stream()
            .map(AgentTaskResponse::from)
            .toList();
    }

    public List<AgentEvent> listEvents(String taskId, int limit) {
        AgentTaskLatestEntity task = latestRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        int normalizedLimit = limit <= 0 ? properties.getListLimit() : Math.min(limit, 500);
        return eventStore.listByTask(task.getTenantId(), task.getSessionId(), taskId, normalizedLimit);
    }

    public Optional<AgentEvent> pollResult(String taskId, long timeoutMs) {
        AgentTaskLatestEntity task = latestRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        try {
            long normalizedTimeout = Math.max(0, Math.min(timeoutMs, 5000));
            AgentEvent event = eventBus.pollResult(taskId, normalizedTimeout, TimeUnit.MILLISECONDS);
            if (event != null) {
                return Optional.of(event);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (!"SUCCESS".equalsIgnoreCase(task.getStatus()) && !"FAILED".equalsIgnoreCase(task.getStatus())) {
            return Optional.empty();
        }
        return eventStore.listByTask(task.getTenantId(), task.getSessionId(), taskId, properties.getListLimit()).stream()
            .filter(event -> "ANSWER".equalsIgnoreCase(event.getType()) || "ERROR".equalsIgnoreCase(event.getType()))
            .reduce((previous, current) -> current);
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        stopping = true;
    }

    private void startWorker(String tenantId) {
        String normalizedTenantId = normalizeTenant(tenantId);
        AtomicBoolean started = workerStates.computeIfAbsent(normalizedTenantId, ignored -> new AtomicBoolean(false));
        if (!started.compareAndSet(false, true)) {
            return;
        }
        taskExecutor.submit(() -> consumeTenantQueue(normalizedTenantId, started));
    }

    private void consumeTenantQueue(String tenantId, AtomicBoolean started) {
        log.info("Agent worker started for tenant={}", tenantId);
        while (!stopping) {
            try {
                AgentEvent event = eventBus.poll(tenantId, 1, TimeUnit.SECONDS);
                if (event != null && "QUESTION".equalsIgnoreCase(event.getType())) {
                    handleQuestion(event);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.error("Agent worker failed for tenant={}", tenantId, ex);
            }
        }
        started.set(false);
        log.info("Agent worker stopped for tenant={}", tenantId);
    }

    private void handleQuestion(AgentEvent question) {
        updateLatest(question.getTaskId(), "RUNNING", null, null);
        saveStatusEvent(question, "RUNNING", Map.of("message", "Agent task is running"));

        try {
            AgentTaskPayload payload = objectMapper.readValue(question.getPayload(), AgentTaskPayload.class);
            InteractionRequest interactionRequest = payload.toInteractionRequest();
            InteractionResponse response = orchestrationService.chat(interactionRequest);
            String answer = response.getAnswer() == null ? "" : response.getAnswer();

            AgentEvent answerEvent = copyEvent(question, "ANSWER", "SUCCESS", writePayload(response));
            eventStore.save(answerEvent);
            updateLatest(question.getTaskId(), "SUCCESS", summarize(answer), null);
            eventBus.publishResult(answerEvent);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            AgentEvent errorEvent = copyEvent(question, "ERROR", "FAILED", writePayload(Map.of("message", message)));
            eventStore.save(errorEvent);
            updateLatest(question.getTaskId(), "FAILED", null, message);
            eventBus.publishResult(errorEvent);
        }
    }

    private void saveStatusEvent(AgentEvent source, String status, Map<String, Object> payload) {
        AgentEvent statusEvent = copyEvent(source, "STATUS", status, writePayload(payload));
        eventStore.save(statusEvent);
    }

    private AgentEvent copyEvent(AgentEvent source, String type, String status, String payload) {
        return AgentEvent.builder()
            .taskId(source.getTaskId())
            .tenantId(source.getTenantId())
            .userId(source.getUserId())
            .agentId(source.getAgentId())
            .sessionId(source.getSessionId())
            .type(type)
            .status(status)
            .payload(payload)
            .build();
    }

    @Transactional
    protected void updateLatest(String taskId, String status, String answerSummary, String errorMessage) {
        latestRepository.findById(taskId).ifPresent(entity -> {
            entity.setStatus(status);
            if (answerSummary != null) {
                entity.setAnswerSummary(answerSummary);
            }
            if (errorMessage != null) {
                entity.setErrorMessage(errorMessage);
            }
            entity.setUpdateTime(Instant.now());
            latestRepository.save(entity);
        });
    }

    private AgentTaskSubmitRequest normalize(AgentTaskSubmitRequest request) {
        request.setTenantId(normalizeTenant(request.getTenantId()));
        request.setUserId(firstText(request.getUserId(), "anonymous"));
        request.setSessionId(firstText(request.getSessionId(), UUID.randomUUID().toString()));
        request.setMode(firstText(request.getMode(), "agent_chat"));
        if (request.getSkillId() == null || request.getSkillId().isBlank()) {
            request.setSkillId(request.getAgentId());
        }
        return request;
    }

    private void validate(AgentTaskSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Task request cannot be null");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
    }

    private String writePayload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize agent event payload", ex);
        }
    }

    private String summarize(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        int maxLength = 500;
        return answer.length() <= maxLength ? answer : answer.substring(0, maxLength);
    }

    private String normalizeTenant(String tenantId) {
        return firstText(tenantId, "default");
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
