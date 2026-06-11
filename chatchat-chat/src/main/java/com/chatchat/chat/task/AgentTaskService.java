package com.chatchat.chat.task;

import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.common.interaction.InteractionToolTrace;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskService {

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL", "WAIT_CONFIRMATION");
    private static final List<String> RECOVERABLE_STATUSES = List.of("PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL");
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCESS", "FAILED", "CANCELLED");
    private static final int MAX_IDLE_POLLS = 3;
    private static final int MAX_CONFIRMATION_ROUNDS = 20;

    private final AgentEventBus eventBus;
    private final AgentEventStore eventStore;
    private final AgentTaskLatestRepository latestRepository;
    private final InteractionOrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;
    private final AgentTaskProperties properties;
    private final ToolRuntimeService toolRuntimeService;
    private final AgentTaskCancellationRegistry cancellationRegistry;

    @Qualifier("agentTaskExecutor")
    private final ThreadPoolTaskExecutor taskExecutor;

    private final Map<String, AtomicBoolean> workerStates = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningTaskThreads = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public AgentTaskResponse submit(AgentTaskSubmitRequest request) {
        validate(request);
        AgentTaskSubmitRequest normalized = normalize(request);
        if (normalized.getResumeTaskId() != null && !normalized.getResumeTaskId().isBlank()) {
            return resumeWaitingTask(normalized.getTenantId(), normalized.getResumeTaskId(), normalized);
        }
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

        queueQuestion(latest, normalized);
        return AgentTaskResponse.from(latest);
    }

    public Optional<AgentTaskResponse> get(String tenantId, String taskId) {
        return Optional.of(AgentTaskResponse.from(getTaskForTenant(tenantId, taskId)));
    }

    public List<AgentTaskResponse> list(String tenantId, String sessionId, int page, int pageSize) {
        String normalizedTenant = requireTenant(tenantId);
        int normalizedPage = Math.max(0, page - 1);
        int normalizedSize = Math.max(1, Math.min(100, pageSize));
        PageRequest pageable = PageRequest.of(normalizedPage, normalizedSize);
        if (sessionId != null && !sessionId.isBlank()) {
            return latestRepository.findByTenantIdAndSessionIdOrderByCreateTimeDesc(normalizedTenant, sessionId.trim(), pageable)
                .stream().map(AgentTaskResponse::from).toList();
        }
        return latestRepository.findByTenantIdOrderByCreateTimeDesc(normalizedTenant, pageable).stream()
            .map(AgentTaskResponse::from)
            .toList();
    }

    public List<AgentEvent> listEvents(String tenantId, String taskId, int limit) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId);
        int normalizedLimit = limit <= 0 ? properties.getListLimit() : Math.min(limit, 500);
        return eventStore.listByTask(task.getTenantId(), task.getSessionId(), taskId, normalizedLimit);
    }

    public AgentRuntimeSummary summarizeRuntime(String tenantId, int latestLimit) {
        String normalizedTenant = requireTenant(tenantId);
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        List<AgentTaskLatestRepository.StatusCount> statusRows = latestRepository.countByTenantIdGroupByStatus(normalizedTenant);
        statusRows.forEach(row -> statusCounts.put(normalizeStatus(row.getStatus()), row.getTotal()));

        long totalTasks = sum(statusCounts.values());
        long pendingTasks = statusCounts.getOrDefault("PENDING", 0L);
        long waitToolTasks = statusCounts.getOrDefault("WAIT_TOOL", 0L);
        long waitModelTasks = statusCounts.getOrDefault("WAIT_MODEL", 0L);
        long runningTasks = statusCounts.getOrDefault("RUNNING", 0L);
        long waitingTasks = waitToolTasks + waitModelTasks;
        long successTasks = statusCounts.getOrDefault("SUCCESS", 0L);
        long failedTasks = statusCounts.getOrDefault("FAILED", 0L);
        long cancelledTasks = statusCounts.getOrDefault("CANCELLED", 0L);
        long activeTasks = pendingTasks + runningTasks + waitingTasks;
        long queueDepth = pendingTasks + waitToolTasks;
        long tenantCount = totalTasks > 0 ? 1 : 0;
        long activeWorkerCount = workerStates.values().stream().filter(AtomicBoolean::get).count();
        int normalizedLimit = Math.max(1, Math.min(latestLimit <= 0 ? 10 : latestLimit, 50));

        List<AgentRuntimeSummary.StatusMetric> statuses = statusCounts.entrySet().stream()
            .map(entry -> new AgentRuntimeSummary.StatusMetric(entry.getKey(), entry.getValue()))
            .toList();
        List<AgentRuntimeSummary.TenantMetric> tenants = List.of(new AgentRuntimeSummary.TenantMetric(normalizedTenant, totalTasks));

        return new AgentRuntimeSummary(
            "Agent Runtime",
            normalizedTenant,
            totalTasks,
            activeTasks,
            pendingTasks,
            waitingTasks,
            successTasks,
            failedTasks,
            cancelledTasks,
            tenantCount,
            activeWorkerCount,
            queueDepth,
            toolRuntimeService.snapshot(),
            statuses,
            tenants,
            list(normalizedTenant, null, 1, normalizedLimit)
        );
    }

    public Optional<AgentEvent> pollResult(String tenantId, String taskId, long timeoutMs) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId);
        try {
            long normalizedTimeout = Math.max(0, Math.min(timeoutMs, 5000));
            AgentEvent event = eventBus.pollResult(taskId, normalizedTimeout, TimeUnit.MILLISECONDS);
            if (event != null) {
                return Optional.of(event);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        AgentTaskLatestEntity latestTask = latestRepository.findById(taskId).orElse(task);
        String latestStatus = normalizeStatus(latestTask.getStatus());
        if (!TERMINAL_STATUSES.contains(latestStatus)) {
            if ("WAIT_CONFIRMATION".equals(latestStatus)) {
                return eventStore.listByTask(latestTask.getTenantId(), latestTask.getSessionId(), taskId, properties.getListLimit()).stream()
                    .filter(event -> "NEEDS_CONFIRMATION".equalsIgnoreCase(event.getType())
                        || "WAIT_CONFIRMATION".equalsIgnoreCase(event.getStatus()))
                    .reduce((previous, current) -> current);
            }
            return Optional.empty();
        }
        return eventStore.listByTask(latestTask.getTenantId(), latestTask.getSessionId(), taskId, properties.getListLimit()).stream()
            .filter(event -> "ANSWER".equalsIgnoreCase(event.getType())
                || "ERROR".equalsIgnoreCase(event.getType())
                || ("STATUS".equalsIgnoreCase(event.getType()) && "CANCELLED".equalsIgnoreCase(event.getStatus())))
            .reduce((previous, current) -> current);
    }

    public AgentTaskResponse cancel(String tenantId, String taskId) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId);
        String currentStatus = normalizeStatus(task.getStatus());
        if (TERMINAL_STATUSES.contains(currentStatus)) {
            return AgentTaskResponse.from(task);
        }
        cancellationRegistry.cancelTask(taskId);
        Thread runningThread = runningTaskThreads.get(taskId);
        if (runningThread != null) {
            runningThread.interrupt();
        }
        updateLatest(task.getTaskId(), "CANCELLED", null, "Task cancelled by tenant request");
        AgentEvent cancelledEvent = saveStatusEvent(task, "CANCELLED", Map.of("message", "Agent task cancelled"));
        eventBus.publishResult(cancelledEvent);
        return get(tenantId, taskId).orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    public AgentTaskResponse retry(String tenantId, String taskId) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId);
        String currentStatus = normalizeStatus(task.getStatus());
        if (!List.of("FAILED", "CANCELLED").contains(currentStatus)) {
            throw new IllegalArgumentException("Only FAILED or CANCELLED tasks can be retried");
        }
        AgentTaskPayload payload = loadQuestionPayload(task);
        AgentTaskSubmitRequest retryRequest = payload.toSubmitRequest();
        retryRequest.setTenantId(task.getTenantId());
        retryRequest.setUserId(task.getUserId());
        retryRequest.setAgentId(task.getAgentId());
        retryRequest.setSessionId(task.getSessionId());
        retryRequest.setQuery(task.getQuestion());
        saveStatusEvent(task, "CANCELLED", Map.of("message", "Task retried and superseded"));
        return submit(retryRequest);
    }

    private AgentTaskResponse resumeWaitingTask(String tenantId, String taskId, AgentTaskSubmitRequest request) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId.trim());
        String currentStatus = normalizeStatus(task.getStatus());
        if (!"WAIT_CONFIRMATION".equals(currentStatus)) {
            throw new IllegalArgumentException("Only WAIT_CONFIRMATION tasks can be resumed");
        }
        request.setTenantId(task.getTenantId());
        request.setSessionId(task.getSessionId());
        request.setResumeTaskId(task.getTaskId());
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            request.setUserId(task.getUserId());
        }
        if (request.getAgentId() == null || request.getAgentId().isBlank()) {
            request.setAgentId(task.getAgentId());
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            request.setQuery(task.getQuestion());
        }
        if (!runningTaskThreads.containsKey(task.getTaskId())) {
            throw new IllegalStateException("No active worker is waiting for MCP confirmation: " + task.getTaskId());
        }
        task.setStatus("RUNNING");
        task.setErrorMessage(null);
        task.setUserId(firstText(request.getUserId(), task.getUserId()));
        task.setAgentId(firstText(request.getAgentId(), task.getAgentId()));
        task.setQuestion(firstText(request.getQuery(), task.getQuestion()));
        latestRepository.save(task);
        eventBus.clearResults(task.getTaskId());
        saveStatusEvent(task, "RUNNING", Map.of("message", "MCP confirmation received"));
        AgentEvent confirmationEvent = AgentEvent.builder()
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(firstText(request.getUserId(), task.getUserId()))
            .agentId(firstText(request.getAgentId(), task.getAgentId()))
            .sessionId(task.getSessionId())
            .type("CONFIRMATION")
            .status("CONFIRMED")
            .payload(writePayload(request))
            .build();
        confirmationEvent.setSequence(nextSequence(task));
        eventStore.save(confirmationEvent);
        eventBus.publishConfirmation(confirmationEvent);
        return AgentTaskResponse.from(task);
    }

    public int reconcileLatestStateFromEvents() {
        List<AgentTaskLatestEntity> tasks = latestRepository.findByStatusInOrderByCreateTimeAsc(RECOVERABLE_STATUSES);
        if (tasks.isEmpty()) {
            return 0;
        }
        int repaired = 0;
        for (AgentTaskLatestEntity task : tasks.stream().limit(properties.getRecoveryBatchSize()).toList()) {
            if (reconcileLatestTaskState(task)) {
                repaired++;
            }
        }
        return repaired;
    }

    public int recoverActiveTasks() {
        List<AgentTaskLatestEntity> tasks = latestRepository.findByStatusInOrderByCreateTimeAsc(ACTIVE_STATUSES);
        if (tasks.isEmpty()) {
            return 0;
        }
        int recovered = 0;
        for (AgentTaskLatestEntity task : tasks.stream().limit(properties.getRecoveryBatchSize()).toList()) {
            AgentEvent questionEvent = loadQuestionEvent(task);
            updateLatest(task.getTaskId(), "PENDING", null, null);
            saveStatusEvent(task, "PENDING", Map.of("message", "Task recovered after runtime restart"));
            startWorker(task.getTenantId());
            eventBus.publish(questionEvent);
            recovered++;
        }
        return recovered;
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
        int idlePolls = 0;
        while (!stopping) {
            try {
                AgentEvent event = eventBus.poll(tenantId, 1, TimeUnit.SECONDS);
                if (event == null) {
                    idlePolls++;
                    if (idlePolls >= MAX_IDLE_POLLS) {
                        break;
                    }
                    continue;
                }
                idlePolls = 0;
                if ("QUESTION".equalsIgnoreCase(event.getType())) {
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
        if (isCancelled(question.getTaskId())) {
            return;
        }
        runningTaskThreads.put(question.getTaskId(), Thread.currentThread());
        updateLatest(question.getTaskId(), "RUNNING", null, null);
        saveStatusEvent(question, "RUNNING", Map.of("message", "Agent task is running"));

        try {
            AgentTaskPayload payload = objectMapper.readValue(question.getPayload(), AgentTaskPayload.class);
            InteractionRequest interactionRequest = payload.toInteractionRequest();
            int confirmationRounds = 0;
            while (!stopping) {
                if (isCancelled(question.getTaskId())) {
                    return;
                }
                updateLatest(question.getTaskId(), "WAIT_MODEL", null, null);
                saveStatusEvent(question, "WAIT_MODEL", Map.of("message", "Agent task is waiting for model inference"));
                long modelStartedAt = System.currentTimeMillis();

                attachCancellationCheck(interactionRequest, question.getTaskId());
                InteractionResponse response = orchestrationService.chat(interactionRequest);
                long modelFinishedAt = System.currentTimeMillis();
                String answer = response.getAnswer() == null ? "" : response.getAnswer();
                if (isCancelled(question.getTaskId())) {
                    return;
                }
                updateLatest(question.getTaskId(), "RUNNING", null, null);
                emitRuntimeEvents(question, response, modelStartedAt, modelFinishedAt);
                if (requiresConfirmation(response)) {
                    confirmationRounds++;
                    if (confirmationRounds > MAX_CONFIRMATION_ROUNDS) {
                        throw new IllegalStateException("MCP confirmation loop exceeded " + MAX_CONFIRMATION_ROUNDS + " rounds");
                    }
                    Map<String, Object> pendingToolExecution = pendingToolExecution(response);
                    AgentEvent confirmationEvent = copyEvent(question, "NEEDS_CONFIRMATION", "WAIT_CONFIRMATION", writePayload(response));
                    confirmationEvent.setSequence(nextSequence(question));
                    confirmationEvent.setParentEventId(question.getEventId());
                    confirmationEvent.setLatencyMs(response.getLatencyMs());
                    confirmationEvent.setCreateTime(resolveAnswerTime(response, modelFinishedAt));
                    eventStore.save(confirmationEvent);
                    updateLatest(question.getTaskId(), "WAIT_CONFIRMATION", null, "Agent task is waiting for MCP confirmation");
                    eventBus.publishResult(confirmationEvent);

                    AgentTaskSubmitRequest confirmationRequest = waitForMcpConfirmation(question);
                    applyMcpConfirmation(interactionRequest, confirmationRequest, pendingToolExecution);
                    updateLatest(question.getTaskId(), "RUNNING", null, null);
                    saveStatusEvent(question, "RUNNING", Map.of("message", "Task resumed after MCP confirmation"));
                    continue;
                }

                AgentEvent answerEvent = copyEvent(question, "ANSWER", "SUCCESS", writePayload(response));
                answerEvent.setSequence(nextSequence(question));
                answerEvent.setParentEventId(question.getEventId());
                answerEvent.setLatencyMs(response.getLatencyMs());
                answerEvent.setCreateTime(resolveAnswerTime(response, modelFinishedAt));
                eventStore.save(answerEvent);
                Map<String, Object> completePayload = new LinkedHashMap<>();
                completePayload.put("message", "Agent task completed");
                completePayload.put("mode", response.getMode());
                completePayload.put("handler", metadataValue(response.getMetadata(), "handler"));
                completePayload.put("toolTraceCount", response.getToolTraces() == null ? 0 : response.getToolTraces().size());
                AgentEvent completeEvent = copyEvent(question, "COMPLETE", "SUCCESS", writePayload(completePayload));
                completeEvent.setSequence(nextSequence(question));
                completeEvent.setParentEventId(answerEvent.getEventId());
                completeEvent.setCreateTime(Math.max(answerEvent.getCreateTime(), System.currentTimeMillis()));
                eventStore.save(completeEvent);
                updateLatest(question.getTaskId(), "SUCCESS", summarize(answer), null);
                eventBus.publishResult(answerEvent);
                return;
            }
            throw new CancellationException("Agent task stopped");
        } catch (CancellationException ex) {
            if (!isCancelled(question.getTaskId())) {
                updateLatest(question.getTaskId(), "CANCELLED", null, firstText(ex.getMessage(), "Task cancelled"));
                AgentEvent cancelledEvent = copyEvent(question, "STATUS", "CANCELLED", writePayload(Map.of(
                    "message", firstText(ex.getMessage(), "Agent task cancelled")
                )));
                cancelledEvent.setSequence(nextSequence(question));
                cancelledEvent.setParentEventId(question.getEventId());
                eventStore.save(cancelledEvent);
                eventBus.publishResult(cancelledEvent);
            }
        } catch (Exception ex) {
            if (isCancelled(question.getTaskId())) {
                return;
            }
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            AgentEvent errorEvent = copyEvent(question, "ERROR", "FAILED", writePayload(Map.of("message", message)));
            errorEvent.setSequence(nextSequence(question));
            errorEvent.setParentEventId(question.getEventId());
            errorEvent.setErrorCode(ex.getClass().getSimpleName());
            eventStore.save(errorEvent);
            AgentEvent completeEvent = copyEvent(question, "COMPLETE", "FAILED", writePayload(Map.of(
                "message", "Agent task failed",
                "error", message
            )));
            completeEvent.setSequence(nextSequence(question));
            completeEvent.setParentEventId(errorEvent.getEventId());
            completeEvent.setErrorCode(ex.getClass().getSimpleName());
            completeEvent.setCreateTime(Math.max(errorEvent.getCreateTime(), System.currentTimeMillis()));
            eventStore.save(completeEvent);
            updateLatest(question.getTaskId(), "FAILED", null, message);
            eventBus.publishResult(errorEvent);
        } finally {
            runningTaskThreads.remove(question.getTaskId());
            eventBus.clearConfirmations(question.getTaskId());
            if (TERMINAL_STATUSES.contains(latestRepository.findById(question.getTaskId())
                .map(AgentTaskLatestEntity::getStatus)
                .map(this::normalizeStatus)
                .orElse("UNKNOWN"))) {
                cancellationRegistry.clear(question.getTaskId());
            }
        }
    }

    private void attachCancellationCheck(InteractionRequest request, String taskId) {
        if (request == null) {
            return;
        }
        Map<String, Object> toolInput = new LinkedHashMap<>(request.getToolInput() == null ? Map.of() : request.getToolInput());
        BooleanSupplier cancellationCheck = () -> isCancelled(taskId);
        toolInput.put("__agentTaskId", taskId);
        toolInput.put("__agentCancellation", cancellationCheck);
        request.setToolInput(toolInput);
    }

    private AgentTaskSubmitRequest waitForMcpConfirmation(AgentEvent question) throws InterruptedException {
        long timeoutMs = Math.max(1L, properties.getConfirmationWaitSeconds()) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopping) {
            if (isCancelled(question.getTaskId())) {
                throw new CancellationException("Agent task cancelled while waiting for MCP confirmation");
            }
            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs <= 0) {
                throw new IllegalStateException("MCP confirmation timed out for task: " + question.getTaskId());
            }
            AgentEvent confirmationEvent = eventBus.pollConfirmation(
                question.getTaskId(),
                Math.min(1000L, remainingMs),
                TimeUnit.MILLISECONDS
            );
            if (confirmationEvent == null) {
                continue;
            }
            try {
                return objectMapper.readValue(confirmationEvent.getPayload(), AgentTaskSubmitRequest.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to deserialize MCP confirmation payload", ex);
            }
        }
        throw new CancellationException("Agent task stopped while waiting for MCP confirmation");
    }

    private void applyMcpConfirmation(InteractionRequest interactionRequest,
                                      AgentTaskSubmitRequest confirmationRequest,
                                      Map<String, Object> pendingToolExecution) {
        if (interactionRequest == null || confirmationRequest == null) {
            return;
        }
        Map<String, Object> confirmationToolInput = confirmationRequest.getToolInput() == null
            ? Map.of()
            : confirmationRequest.getToolInput();
        Object confirmation = confirmationToolInput.get("mcpConfirmation");
        if (confirmation == null) {
            return;
        }
        Map<String, Object> toolInput = new LinkedHashMap<>(interactionRequest.getToolInput() == null
            ? Map.of()
            : interactionRequest.getToolInput());
        toolInput.put("mcpConfirmation", confirmation);
        if (pendingToolExecution != null && !pendingToolExecution.isEmpty()) {
            toolInput.put("mcpPendingToolExecution", pendingToolExecution);
        }
        interactionRequest.setToolInput(toolInput);
    }

    private Map<String, Object> pendingToolExecution(InteractionResponse response) {
        if (response == null || response.getToolTraces() == null || response.getToolTraces().isEmpty()) {
            return Map.of();
        }
        return response.getToolTraces().stream()
            .filter(trace -> {
                Map<String, Object> runtime = trace.getRuntimeMetadata();
                Object outcome = runtime == null ? null : runtime.get("outcome");
                return "confirmation_required".equalsIgnoreCase(String.valueOf(outcome));
            })
            .findFirst()
            .map(trace -> {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("toolName", trace.getToolName());
                pending.put("input", trace.getInput() == null ? Map.of() : trace.getInput());
                Map<String, Object> runtime = trace.getRuntimeMetadata() == null ? Map.of() : trace.getRuntimeMetadata();
                Object executionPlan = runtime.get("executionPlan");
                if (executionPlan instanceof Map<?, ?>) {
                    pending.put("executionPlan", executionPlan);
                }
                Object confirmation = runtime.get("confirmation");
                if (confirmation instanceof Map<?, ?>) {
                    pending.put("confirmation", confirmation);
                }
                return pending;
            })
            .orElse(Map.of());
    }

    private AgentEvent saveStatusEvent(AgentEvent source, String status, Map<String, Object> payload) {
        AgentEvent statusEvent = copyEvent(source, "STATUS", status, writePayload(payload));
        statusEvent.setParentEventId(source.getEventId());
        statusEvent.setSequence(nextSequence(source));
        eventStore.save(statusEvent);
        return statusEvent;
    }

    private AgentEvent saveStatusEvent(AgentTaskLatestEntity source, String status, Map<String, Object> payload) {
        AgentEvent statusEvent = AgentEvent.builder()
            .taskId(source.getTaskId())
            .tenantId(source.getTenantId())
            .userId(source.getUserId())
            .agentId(source.getAgentId())
            .sessionId(source.getSessionId())
            .type("STATUS")
            .status(status)
            .payload(writePayload(payload))
            .build();
        statusEvent.setSequence(nextSequence(source));
        eventStore.save(statusEvent);
        return statusEvent;
    }

    private void emitRuntimeEvents(AgentEvent question,
                                   InteractionResponse response,
                                   long modelStartedAt,
                                   long modelFinishedAt) {
        emitThinkEvent(question, response, modelStartedAt, modelFinishedAt);
        emitPlannerEvents(question, response);
        emitToolEvents(question, response);
    }

    private void emitThinkEvent(AgentEvent question,
                                InteractionResponse response,
                                long modelStartedAt,
                                long modelFinishedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", response.getMode());
        payload.put("handler", metadataValue(response.getMetadata(), "handler"));
        payload.put("historyUsed", metadataValue(response.getMetadata(), "historyUsed"));
        payload.put("latencyMs", response.getLatencyMs());
        AgentEvent thinkEvent = copyEvent(question, "THINK", "RUNNING", writePayload(payload));
        thinkEvent.setParentEventId(question.getEventId());
        thinkEvent.setSequence(nextSequence(question));
        thinkEvent.setLatencyMs(Math.max(0L, modelFinishedAt - modelStartedAt));
        thinkEvent.setCreateTime(modelStartedAt);
        eventStore.save(thinkEvent);
    }

    @SuppressWarnings("unchecked")
    private void emitPlannerEvents(AgentEvent question, InteractionResponse response) {
        List<Map<String, Object>> plannerSteps = plannerSteps(response);
        for (Map<String, Object> step : plannerSteps) {
            long plannedAt = longValue(step.get("plannedAt"), System.currentTimeMillis());
            Map<String, Object> planPayload = new LinkedHashMap<>();
            planPayload.put("step", step.get("step"));
            planPayload.put("action", step.get("action"));
            planPayload.put("toolName", step.get("toolName"));
            planPayload.put("reason", step.get("reason"));
            planPayload.put("answerPreview", step.get("answerPreview"));
            planPayload.put("observationCount", step.get("observationCount"));

            AgentEvent planEvent = copyEvent(question, "PLAN", "RUNNING", writePayload(planPayload));
            planEvent.setParentEventId(question.getEventId());
            planEvent.setSequence(nextSequence(question));
            planEvent.setToolName(stringValue(step.get("toolName")));
            planEvent.setCreateTime(plannedAt);
            eventStore.save(planEvent);

            String reason = stringValue(step.get("reason"));
            String preview = stringValue(step.get("answerPreview"));
            if ((reason != null && !reason.isBlank()) || (preview != null && !preview.isBlank())) {
                Map<String, Object> thinkPayload = new LinkedHashMap<>();
                thinkPayload.put("step", step.get("step"));
                thinkPayload.put("reason", reason);
                thinkPayload.put("answerPreview", preview);
                thinkPayload.put("action", step.get("action"));
                AgentEvent thinkEvent = copyEvent(question, "THINK", "RUNNING", writePayload(thinkPayload));
                thinkEvent.setParentEventId(planEvent.getEventId());
                thinkEvent.setSequence(nextSequence(question));
                thinkEvent.setToolName(stringValue(step.get("toolName")));
                thinkEvent.setCreateTime(plannedAt);
                eventStore.save(thinkEvent);
            }
        }
    }

    private void emitToolEvents(AgentEvent question, InteractionResponse response) {
        List<InteractionToolTrace> traces = response.getToolTraces() == null ? List.of() : response.getToolTraces();
        for (InteractionToolTrace trace : traces) {
            long startedAt = trace.getStartedAt() == null ? System.currentTimeMillis() : trace.getStartedAt();
            long finishedAt = trace.getFinishedAt() == null ? startedAt : trace.getFinishedAt();

            AgentEvent waitToolEvent = copyEvent(question, "STATUS", "WAIT_TOOL", writePayload(Map.of(
                "message", "Agent task is waiting for tool execution",
                "toolName", trace.getToolName()
            )));
            waitToolEvent.setParentEventId(question.getEventId());
            waitToolEvent.setSequence(nextSequence(question));
            waitToolEvent.setToolName(trace.getToolName());
            waitToolEvent.setCreateTime(startedAt);
            eventStore.save(waitToolEvent);

            Map<String, Object> toolCallPayload = new LinkedHashMap<>();
            toolCallPayload.put("toolName", trace.getToolName());
            toolCallPayload.put("displayName", trace.getDisplayName());
            toolCallPayload.put("serviceId", trace.getServiceId());
            toolCallPayload.put("serviceName", trace.getServiceName());
            toolCallPayload.put("input", trace.getInput());
            toolCallPayload.put("runtime", trace.getRuntimeMetadata());
            AgentEvent toolCallEvent = copyEvent(question, "TOOL_CALL", "WAIT_TOOL", writePayload(toolCallPayload));
            toolCallEvent.setParentEventId(waitToolEvent.getEventId());
            toolCallEvent.setSequence(nextSequence(question));
            toolCallEvent.setToolName(trace.getToolName());
            toolCallEvent.setCreateTime(startedAt);
            eventStore.save(toolCallEvent);

            Map<String, Object> toolResultPayload = new LinkedHashMap<>();
            toolResultPayload.put("toolName", trace.getToolName());
            toolResultPayload.put("success", trace.isSuccess());
            toolResultPayload.put("output", trace.getOutput());
            toolResultPayload.put("errorMessage", trace.getErrorMessage());
            toolResultPayload.put("durationMs", trace.getDurationMs());
            toolResultPayload.put("runtime", trace.getRuntimeMetadata());
            AgentEvent toolResultEvent = copyEvent(
                question,
                "TOOL_RESULT",
                trace.isSuccess() ? "RUNNING" : "FAILED",
                writePayload(toolResultPayload)
            );
            toolResultEvent.setParentEventId(toolCallEvent.getEventId());
            toolResultEvent.setSequence(nextSequence(question));
            toolResultEvent.setToolName(trace.getToolName());
            toolResultEvent.setLatencyMs(trace.getDurationMs());
            toolResultEvent.setErrorCode(trace.isSuccess() ? null : "TOOL_EXECUTION_FAILED");
            toolResultEvent.setCreateTime(finishedAt);
            eventStore.save(toolResultEvent);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> plannerSteps(InteractionResponse response) {
        if (response == null || response.getMetadata() == null) {
            return List.of();
        }
        Object direct = response.getMetadata().get("plannerSteps");
        if (direct instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        Object agent = response.getMetadata().get("agent");
        if (agent instanceof Map<?, ?> agentMap) {
            Object nested = agentMap.get("plannerSteps");
            if (nested instanceof List<?> list) {
                return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
            }
        }
        return List.of();
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

    private void queueQuestion(AgentTaskLatestEntity latest, AgentTaskSubmitRequest request) {
        queueQuestion(latest, request, true);
    }

    private void queueQuestion(AgentTaskLatestEntity latest, AgentTaskSubmitRequest request, boolean persistQuestionEvent) {
        AgentEvent question = AgentEvent.builder()
            .taskId(latest.getTaskId())
            .tenantId(latest.getTenantId())
            .userId(latest.getUserId())
            .agentId(latest.getAgentId())
            .sessionId(latest.getSessionId())
            .type("QUESTION")
            .status("PENDING")
            .payload(writePayload(new AgentTaskPayload(request)))
            .build();
        question.setSequence(nextSequence(question));
        if (persistQuestionEvent) {
            eventStore.save(question);
        }
        startWorker(latest.getTenantId());
        eventBus.publish(question);
    }

    private AgentTaskPayload loadQuestionPayload(AgentTaskLatestEntity task) {
        return readPayload(loadQuestionEvent(task));
    }

    private AgentEvent loadQuestionEvent(AgentTaskLatestEntity task) {
        return eventStore.findFirstByTaskAndType(task.getTenantId(), task.getSessionId(), task.getTaskId(), "QUESTION")
            .orElseThrow(() -> new IllegalStateException("Question payload not found for task: " + task.getTaskId()));
    }

    private AgentTaskPayload readPayload(AgentEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), AgentTaskPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize agent task payload", ex);
        }
    }

    private AgentTaskLatestEntity getTaskForTenant(String tenantId, String taskId) {
        String normalizedTenant = requireTenant(tenantId);
        AgentTaskLatestEntity task = latestRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!normalizedTenant.equals(task.getTenantId())) {
            throw new IllegalArgumentException("Task not found for tenant: " + taskId);
        }
        return task;
    }

    private long nextSequence(AgentEvent event) {
        return eventStore.nextSequence(event.getTenantId(), event.getSessionId(), event.getTaskId());
    }

    private long nextSequence(AgentTaskLatestEntity task) {
        return eventStore.nextSequence(task.getTenantId(), task.getSessionId(), task.getTaskId());
    }

    private boolean reconcileLatestTaskState(AgentTaskLatestEntity task) {
        List<AgentEvent> events = eventStore.listByTask(task.getTenantId(), task.getSessionId(), task.getTaskId(), Integer.MAX_VALUE);
        if (events.isEmpty()) {
            return false;
        }
        AgentEvent terminalEvent = findLatestTerminalEvent(events);
        if (terminalEvent == null) {
            return false;
        }
        String derivedStatus = normalizeStatus(terminalEvent.getStatus());
        if (!TERMINAL_STATUSES.contains(derivedStatus)) {
            return false;
        }
        if (Objects.equals(derivedStatus, normalizeStatus(task.getStatus()))) {
            return false;
        }

        String answerSummary = null;
        String errorMessage = null;
        if ("SUCCESS".equals(derivedStatus)) {
            answerSummary = findLatestEvent(events, "ANSWER")
                .map(this::extractAnswerSummary)
                .orElse(task.getAnswerSummary());
            errorMessage = "";
        } else if ("FAILED".equals(derivedStatus)) {
            errorMessage = extractErrorMessage(terminalEvent);
        } else if ("CANCELLED".equals(derivedStatus)) {
            errorMessage = "Task cancelled";
        }

        updateLatest(task.getTaskId(), derivedStatus, answerSummary, errorMessage);
        return true;
    }

    private AgentEvent findLatestTerminalEvent(List<AgentEvent> events) {
        return events.stream()
            .filter(event -> TERMINAL_STATUSES.contains(normalizeStatus(event.getStatus())))
            .reduce((previous, current) -> current)
            .orElse(null);
    }

    private Optional<AgentEvent> findLatestEvent(List<AgentEvent> events, String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return events.stream()
            .filter(event -> type.equalsIgnoreCase(event.getType()))
            .reduce((previous, current) -> current);
    }

    private String extractAnswerSummary(AgentEvent event) {
        if (event == null || event.getPayload() == null || event.getPayload().isBlank()) {
            return "";
        }
        try {
            return summarize(objectMapper.readTree(event.getPayload()).path("answer").asText(""));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize answer payload", ex);
        }
    }

    private String extractErrorMessage(AgentEvent event) {
        if (event == null) {
            return "";
        }
        String payload = event.getPayload();
        if (payload == null || payload.isBlank()) {
            return firstText(event.getErrorCode(), "");
        }
        try {
            String message = objectMapper.readTree(payload).path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
            return objectMapper.readTree(payload).path("error").asText(firstText(event.getErrorCode(), ""));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize error payload", ex);
        }
    }

    private long resolveAnswerTime(InteractionResponse response, long fallbackTime) {
        if (response != null && response.getTimestamp() != null && response.getTimestamp() > 0) {
            return response.getTimestamp();
        }
        return fallbackTime;
    }

    private Object metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        if (metadata.containsKey(key)) {
            return metadata.get(key);
        }
        Object agent = metadata.get("agent");
        if (agent instanceof Map<?, ?> agentMap) {
            return agentMap.get(key);
        }
        return null;
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isCancelled(String taskId) {
        return cancellationRegistry.isCancelled(taskId) || latestRepository.findById(taskId)
            .map(AgentTaskLatestEntity::getStatus)
            .map(this::normalizeStatus)
            .filter("CANCELLED"::equals)
            .isPresent();
    }

    private boolean requiresConfirmation(InteractionResponse response) {
        if (response == null) {
            return false;
        }
        if (Boolean.TRUE.equals(metadataValue(response.getMetadata(), "confirmationRequired"))) {
            return true;
        }
        List<InteractionToolTrace> traces = response.getToolTraces() == null ? List.of() : response.getToolTraces();
        return traces.stream().anyMatch(trace -> {
            Map<String, Object> runtime = trace.getRuntimeMetadata();
            Object outcome = runtime == null ? null : runtime.get("outcome");
            return "confirmation_required".equalsIgnoreCase(String.valueOf(outcome));
        });
    }

    private AgentTaskSubmitRequest normalize(AgentTaskSubmitRequest request) {
        request.setTenantId(requireTenant(request.getTenantId()));
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
        return requireTenant(tenantId);
    }

    private String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        return tenantId.trim();
    }

    private String normalizeStatus(String status) {
        return firstText(status, "UNKNOWN").toUpperCase();
    }

    private long sum(Collection<Long> values) {
        return values.stream().mapToLong(Long::longValue).sum();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
