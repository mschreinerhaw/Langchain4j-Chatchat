package com.chatchat.chat.task;

import com.chatchat.agents.runtime.AgentRuntime;
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

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL", "WAIT_CONFIRMATION", "WAITING_CONFIRM");
    private static final List<String> RECOVERABLE_STATUSES = List.of("PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL");
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCESS", "FAILED", "CANCELLED", "REJECTED", "TIMEOUT_CANCELLED", "KILLED");
    private static final int MAX_IDLE_POLLS = 3;
    private static final int MAX_CONFIRMATION_ROUNDS = 20;

    private final AgentEventBus eventBus;
    private final AgentEventStore eventStore;
    private final AgentTaskLatestRepository latestRepository;
    private final InteractionOrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;
    private final AgentTaskProperties properties;
    private final ToolRuntimeService toolRuntimeService;
    private final AgentRuntime agentRuntime;
    private final AgentTaskCancellationRegistry cancellationRegistry;
    private final AgentLearningService learningService;
    private final TaskConfirmRepository taskConfirmRepository;

    @Qualifier("agentTaskExecutor")
    private final ThreadPoolTaskExecutor taskExecutor;

    private final Map<String, AtomicBoolean> workerStates = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningTaskThreads = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    /**
     * Performs the submit operation.
     *
     * @param request the request value
     * @return the operation result
     */
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

    /**
     * Returns the get.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @return the get
     */
    public Optional<AgentTaskResponse> get(String tenantId, String taskId) {
        return Optional.of(AgentTaskResponse.from(getTaskForTenant(tenantId, taskId)));
    }

    /**
     * Lists the list.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param page the page value
     * @param pageSize the page size value
     * @return the list list
     */
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

    /**
     * Lists the events.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @param limit the limit value
     * @return the events list
     */
    public List<AgentEvent> listEvents(String tenantId, String taskId, int limit) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId);
        int normalizedLimit = limit <= 0 ? properties.getListLimit() : Math.min(limit, 500);
        return eventStore.listByTask(task.getTenantId(), task.getSessionId(), taskId, normalizedLimit);
    }

    /**
     * Performs the summarize runtime operation.
     *
     * @param tenantId the tenant id value
     * @param latestLimit the latest limit value
     * @return the operation result
     */
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

    /**
     * Summarizes product-discovery feedback and adoption signals.
     *
     * @param tenantId the tenant id value
     * @param lowScoreLimit the low score task limit value
     * @return the operation result
     */
    public AgentEffectAnalytics summarizeEffectAnalytics(String tenantId, int lowScoreLimit) {
        String normalizedTenant = requireTenant(tenantId);
        AgentTaskLatestRepository.DiscoveryAggregate aggregate =
            latestRepository.summarizeDiscoveryByTenant(normalizedTenant);
        long totalTasks = longValue(aggregate == null ? null : aggregate.getTotalTasks());
        long successTasks = longValue(aggregate == null ? null : aggregate.getSuccessTasks());
        long failedTasks = longValue(aggregate == null ? null : aggregate.getFailedTasks());
        long cancelledTasks = longValue(aggregate == null ? null : aggregate.getCancelledTasks());
        long feedbackTasks = longValue(aggregate == null ? null : aggregate.getFeedbackTasks());
        long usefulTasks = longValue(aggregate == null ? null : aggregate.getUsefulTasks());
        long adoptedTasks = longValue(aggregate == null ? null : aggregate.getAdoptedTasks());
        long resolvedTasks = longValue(aggregate == null ? null : aggregate.getResolvedTasks());
        int normalizedLimit = Math.max(1, Math.min(lowScoreLimit <= 0 ? 10 : lowScoreLimit, 50));

        List<AgentEffectAnalytics.AgentMetric> agents = latestRepository
            .summarizeDiscoveryByAgent(normalizedTenant, PageRequest.of(0, 12))
            .stream()
            .map(this::toAgentMetric)
            .toList();
        List<AgentEffectAnalytics.ReasonMetric> reasonMetrics = latestRepository
            .countFeedbackReasonsByTenant(normalizedTenant)
            .stream()
            .map(row -> new AgentEffectAnalytics.ReasonMetric(
                row.getReasonCategory(),
                feedbackReasonLabel(row.getReasonCategory()),
                row.getTotal(),
                percentage(row.getTotal(), feedbackTasks)
            ))
            .toList();
        List<AgentTaskResponse> lowScoreTasks = latestRepository
            .findLowScoreFeedbackTasks(normalizedTenant, PageRequest.of(0, normalizedLimit))
            .stream()
            .map(AgentTaskResponse::from)
            .toList();

        return new AgentEffectAnalytics(
            normalizedTenant,
            totalTasks,
            successTasks,
            failedTasks,
            cancelledTasks,
            feedbackTasks,
            usefulTasks,
            adoptedTasks,
            resolvedTasks,
            percentage(usefulTasks, feedbackTasks),
            percentage(adoptedTasks, feedbackTasks),
            percentage(resolvedTasks, feedbackTasks),
            percentage(failedTasks, totalTasks),
            reasonMetrics,
            agents,
            lowScoreTasks
        );
    }

    /**
     * Performs the poll result operation.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @param timeoutMs the timeout ms value
     * @return the operation result
     */
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
            if ("WAIT_CONFIRMATION".equals(latestStatus) || "WAITING_CONFIRM".equals(latestStatus)) {
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
                || ("STATUS".equalsIgnoreCase(event.getType()) && TERMINAL_STATUSES.contains(normalizeStatus(event.getStatus()))))
            .reduce((previous, current) -> current);
    }

    /**
     * Returns whether cancel.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @return whether the condition is satisfied
     */
    public AgentTaskResponse cancel(String tenantId, String taskId) {
        return stopTask(tenantId, taskId, "CANCELLED", "Task cancelled by tenant request", "Agent task cancelled");
    }

    public AgentTaskResponse kill(String tenantId, String taskId) {
        return stopTask(tenantId, taskId, "KILLED", "Task killed by runtime request", "Agent task killed");
    }

    public AgentTaskResponse reject(String tenantId, String taskId, String userId) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId);
        markLatestConfirmation(task.getTaskId(), "REJECTED", userId);
        return stopTask(tenantId, taskId, "REJECTED", "MCP confirmation rejected", "Agent task rejected");
    }

    public AgentTaskResponse confirm(String tenantId, String taskId, AgentTaskSubmitRequest request) {
        String normalizedTaskId = requireText(taskId, "Task ID cannot be empty");
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, normalizedTaskId);
        validateConfirmationBeforeResume(normalizedTaskId, request == null ? null : request.getUserId());
        if (request == null) {
            request = new AgentTaskSubmitRequest();
        }
        request.setTenantId(task.getTenantId());
        request.setResumeTaskId(normalizedTaskId);
        request.setUserId(firstText(request.getUserId(), task.getUserId()));
        request.setAgentId(firstText(request.getAgentId(), task.getAgentId()));
        request.setSessionId(firstText(request.getSessionId(), task.getSessionId()));
        request.setQuery(firstText(request.getQuery(), task.getQuestion()));
        return submit(request);
    }

    @Transactional
    public int cancelExpiredConfirmations() {
        List<TaskConfirmEntity> expiredConfirmations = taskConfirmRepository
            .findByStatusAndExpiredAtBeforeOrderByExpiredAtAsc("WAITING_CONFIRM", Instant.now())
            .stream()
            .limit(properties.getRecoveryBatchSize())
            .toList();
        int cancelled = 0;
        for (TaskConfirmEntity confirmation : expiredConfirmations) {
            Optional<AgentTaskLatestEntity> task = latestRepository.findById(confirmation.getTaskId());
            if (task.isEmpty()) {
                markConfirmation(confirmation, "TIMEOUT_CANCELLED", null);
                continue;
            }
            String currentStatus = normalizeStatus(task.get().getStatus());
            if (!"WAIT_CONFIRMATION".equals(currentStatus) && !"WAITING_CONFIRM".equals(currentStatus)) {
                markConfirmation(confirmation, terminalConfirmStatus(currentStatus), null);
                continue;
            }
            markConfirmation(confirmation, "TIMEOUT_CANCELLED", null);
            stopTask(task.get(), "TIMEOUT_CANCELLED", "MCP confirmation timed out", "Agent task timed out waiting for confirmation");
            cancelled++;
        }
        return cancelled;
    }

    /**
     * Performs the retry operation.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @return the operation result
     */
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

    /**
     * Records user feedback for one completed Agent task.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @param request the feedback request value
     * @return the updated task
     */
    @Transactional
    public AgentTaskResponse recordFeedback(String tenantId, String taskId, AgentTaskFeedbackRequest request) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId);
        String currentStatus = normalizeStatus(task.getStatus());
        if (!TERMINAL_STATUSES.contains(currentStatus)) {
            throw new IllegalArgumentException("Only completed tasks can receive feedback");
        }
        if (request == null || (request.getUseful() == null
            && request.getAdopted() == null
            && request.getResolved() == null
            && (request.getComment() == null || request.getComment().isBlank())
            && (request.getReasonCategory() == null || request.getReasonCategory().isBlank()))) {
            throw new IllegalArgumentException("Feedback cannot be empty");
        }

        task.setFeedbackUseful(request.getUseful());
        task.setFeedbackAdopted(request.getAdopted());
        task.setFeedbackResolved(request.getResolved());
        task.setFeedbackComment(truncate(request.getComment(), 1000));
        task.setFeedbackReasonCategory(normalizeFeedbackReason(request.getReasonCategory()));
        task.setFeedbackTime(Instant.now());
        AgentTaskLatestEntity saved = latestRepository.save(task);
        saveFeedbackEvent(saved, firstText(request.getUserId(), saved.getUserId()));
        try {
            learningService.recordExperience(saved, request);
        } catch (Exception ex) {
            log.warn("Failed to record Agent experience for taskId={}: {}", saved.getTaskId(), ex.getMessage());
        }
        return AgentTaskResponse.from(saved);
    }

    /**
     * Performs the resume waiting task operation.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @param request the request value
     * @return the operation result
     */
    private AgentTaskResponse resumeWaitingTask(String tenantId, String taskId, AgentTaskSubmitRequest request) {
        AgentTaskLatestEntity task = getTaskForTenant(tenantId, taskId.trim());
        String currentStatus = normalizeStatus(task.getStatus());
        if (!"WAIT_CONFIRMATION".equals(currentStatus) && !"WAITING_CONFIRM".equals(currentStatus)) {
            throw new IllegalArgumentException("Only WAIT_CONFIRMATION tasks can be resumed");
        }
        validateConfirmationBeforeResume(task.getTaskId(), request.getUserId());
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
        markLatestConfirmation(task.getTaskId(), "CONFIRMED", request.getUserId());
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

    /**
     * Performs the reconcile latest state from events operation.
     *
     * @return the operation result
     */
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

    /**
     * Performs the recover active tasks operation.
     *
     * @return the operation result
     */
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

    /**
     * Performs the shutdown operation.
     */
    @PreDestroy
    public void shutdown() {
        stopping = true;
    }

    /**
     * Performs the on context closed operation.
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        stopping = true;
    }

    /**
     * Performs the start worker operation.
     *
     * @param tenantId the tenant id value
     */
    private void startWorker(String tenantId) {
        String normalizedTenantId = normalizeTenant(tenantId);
        AtomicBoolean started = workerStates.computeIfAbsent(normalizedTenantId, ignored -> new AtomicBoolean(false));
        if (!started.compareAndSet(false, true)) {
            return;
        }
        taskExecutor.submit(() -> consumeTenantQueue(normalizedTenantId, started));
    }

    /**
     * Performs the consume tenant queue operation.
     *
     * @param tenantId the tenant id value
     * @param started the started value
     */
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

    /**
     * Handles the question.
     *
     * @param question the question value
     */
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
                    TaskConfirmEntity confirmation = createPendingConfirmation(question, response, pendingToolExecution);
                    AgentEvent confirmationEvent = copyEvent(
                        question,
                        "NEEDS_CONFIRMATION",
                        "WAIT_CONFIRMATION",
                        writePayload(confirmationResponsePayload(response, confirmation))
                    );
                    confirmationEvent.setSequence(nextSequence(question));
                    confirmationEvent.setParentEventId(question.getEventId());
                    confirmationEvent.setLatencyMs(response.getLatencyMs());
                    confirmationEvent.setCreateTime(resolveAnswerTime(response, modelFinishedAt));
                    eventStore.save(confirmationEvent);
                    updateLatest(question.getTaskId(), "WAIT_CONFIRMATION", null, "Agent task is waiting for MCP confirmation");
                    eventBus.publishResult(confirmationEvent);

                    AgentTaskSubmitRequest confirmationRequest = waitForMcpConfirmation(question, confirmation.getExpiredAt());
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
                String status = ex instanceof AgentTaskStoppedException stopped ? stopped.status : "CANCELLED";
                updateLatest(question.getTaskId(), status, null, firstText(ex.getMessage(), "Task cancelled"));
                AgentEvent cancelledEvent = copyEvent(question, "STATUS", status, writePayload(Map.of(
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

    /**
     * Performs the attach cancellation check operation.
     *
     * @param request the request value
     * @param taskId the task id value
     */
    private void attachCancellationCheck(InteractionRequest request, String taskId) {
        if (request == null) {
            return;
        }
        Map<String, Object> toolInput = new LinkedHashMap<>(request.getToolInput() == null ? Map.of() : request.getToolInput());
        BooleanSupplier cancellationCheck = () -> isCancelled(taskId);
        toolInput.put("__agentTaskId", taskId);
        toolInput.put("__agentRunId", taskId);
        toolInput.put("__agentCancellation", cancellationCheck);
        request.setToolInput(toolInput);
    }

    /**
     * Performs the wait for mcp confirmation operation.
     *
     * @param question the question value
     * @return the operation result
     * @throws InterruptedException if the operation fails
     */
    private AgentTaskSubmitRequest waitForMcpConfirmation(AgentEvent question, Instant expiredAt) throws InterruptedException {
        long timeoutMs = Math.max(1L, properties.getConfirmationWaitSeconds()) * 1000L;
        long configuredDeadline = System.currentTimeMillis() + timeoutMs;
        long persistedDeadline = expiredAt == null ? configuredDeadline : expiredAt.toEpochMilli();
        long deadline = Math.min(configuredDeadline, persistedDeadline);
        while (!stopping) {
            if (isCancelled(question.getTaskId())) {
                throw new CancellationException("Agent task cancelled while waiting for MCP confirmation");
            }
            enforcePendingConfirmation(question.getTaskId());
            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs <= 0) {
                markLatestConfirmation(question.getTaskId(), "TIMEOUT_CANCELLED", null);
                throw new AgentTaskStoppedException("TIMEOUT_CANCELLED", "MCP confirmation timed out for task: " + question.getTaskId());
            }
            AgentEvent confirmationEvent = eventBus.pollConfirmation(
                question.getTaskId(),
                Math.min(1000L, remainingMs),
                TimeUnit.MILLISECONDS
            );
            if (confirmationEvent == null) {
                continue;
            }
            enforcePendingConfirmation(question.getTaskId());
            try {
                return objectMapper.readValue(confirmationEvent.getPayload(), AgentTaskSubmitRequest.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to deserialize MCP confirmation payload", ex);
            }
        }
        throw new CancellationException("Agent task stopped while waiting for MCP confirmation");
    }

    /**
     * Performs the apply mcp confirmation operation.
     *
     * @param interactionRequest the interaction request value
     * @param confirmationRequest the confirmation request value
     * @param pendingToolExecution the pending tool execution value
     */
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

    private TaskConfirmEntity createPendingConfirmation(AgentEvent question,
                                                        InteractionResponse response,
                                                        Map<String, Object> pendingToolExecution) {
        taskConfirmRepository.findTopByTaskIdOrderByCreatedAtDesc(question.getTaskId())
            .filter(confirm -> "WAITING_CONFIRM".equals(normalizeStatus(confirm.getStatus())))
            .ifPresent(confirm -> markConfirmation(confirm, "EXPIRED", null));
        TaskConfirmEntity confirmation = new TaskConfirmEntity();
        confirmation.setTaskId(question.getTaskId());
        confirmation.setToolName(truncate(resolveToolName(response, pendingToolExecution), 200));
        confirmation.setConfirmMessage(truncate(resolveConfirmationMessage(response, pendingToolExecution), 2000));
        confirmation.setStatus("WAITING_CONFIRM");
        confirmation.setExpiredAt(Instant.now().plusSeconds(Math.max(1L, properties.getConfirmationWaitSeconds())));
        return taskConfirmRepository.save(confirmation);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> confirmationResponsePayload(InteractionResponse response, TaskConfirmEntity confirmation) {
        Map<String, Object> payload = response == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(objectMapper.convertValue(response, Map.class));
        payload.put("confirmId", confirmation.getId());
        payload.put("confirmStatus", confirmation.getStatus());
        payload.put("expiredAt", confirmation.getExpiredAt());
        payload.put("confirmExpiredAt", confirmation.getExpiredAt());
        payload.put("confirmMessage", confirmation.getConfirmMessage());
        payload.put("toolName", confirmation.getToolName());
        return payload;
    }

    private void validateConfirmationBeforeResume(String taskId, String userId) {
        TaskConfirmEntity confirmation = taskConfirmRepository.findTopByTaskIdOrderByCreatedAtDesc(taskId)
            .orElseThrow(() -> new IllegalStateException("Confirmation node not found for task: " + taskId));
        String status = normalizeStatus(confirmation.getStatus());
        if (!"WAITING_CONFIRM".equals(status)) {
            throw new IllegalStateException("Confirmation node is not pending: " + status);
        }
        if (confirmation.getExpiredAt() != null && confirmation.getExpiredAt().isBefore(Instant.now())) {
            markConfirmation(confirmation, "TIMEOUT_CANCELLED", userId);
            throw new IllegalStateException("Confirmation node expired for task: " + taskId);
        }
    }

    private void enforcePendingConfirmation(String taskId) {
        Optional<TaskConfirmEntity> latestConfirmation = taskConfirmRepository.findTopByTaskIdOrderByCreatedAtDesc(taskId);
        if (latestConfirmation.isEmpty()) {
            return;
        }
        TaskConfirmEntity confirmation = latestConfirmation.get();
        String status = normalizeStatus(confirmation.getStatus());
        if ("WAITING_CONFIRM".equals(status)) {
            if (confirmation.getExpiredAt() != null && confirmation.getExpiredAt().isBefore(Instant.now())) {
                markConfirmation(confirmation, "TIMEOUT_CANCELLED", null);
                throw new AgentTaskStoppedException("TIMEOUT_CANCELLED", "MCP confirmation timed out for task: " + taskId);
            }
            return;
        }
        if ("CONFIRMED".equals(status)) {
            return;
        }
        throw new AgentTaskStoppedException(status, "MCP confirmation ended with status: " + status);
    }

    private void markLatestConfirmation(String taskId, String status, String userId) {
        taskConfirmRepository.findTopByTaskIdOrderByCreatedAtDesc(taskId)
            .ifPresent(confirmation -> markConfirmation(confirmation, status, userId));
    }

    private void markConfirmation(TaskConfirmEntity confirmation, String status, String userId) {
        confirmation.setStatus(status);
        confirmation.setConfirmedBy(firstText(userId, confirmation.getConfirmedBy()));
        confirmation.setConfirmedAt(Instant.now());
        taskConfirmRepository.save(confirmation);
    }

    private AgentTaskResponse stopTask(String tenantId,
                                       String taskId,
                                       String status,
                                       String errorMessage,
                                       String eventMessage) {
        return stopTask(getTaskForTenant(tenantId, taskId), status, errorMessage, eventMessage);
    }

    private AgentTaskResponse stopTask(AgentTaskLatestEntity task,
                                       String status,
                                       String errorMessage,
                                       String eventMessage) {
        String currentStatus = normalizeStatus(task.getStatus());
        if (TERMINAL_STATUSES.contains(currentStatus)) {
            return AgentTaskResponse.from(task);
        }
        cancellationRegistry.cancelTask(task.getTaskId());
        try {
            agentRuntime.cancel(task.getTaskId());
        } catch (RuntimeException ex) {
            log.debug("Agent runtime cancel skipped for taskId={}: {}", task.getTaskId(), ex.getMessage());
        }
        Thread runningThread = runningTaskThreads.get(task.getTaskId());
        if (runningThread != null) {
            runningThread.interrupt();
        }
        updateLatest(task.getTaskId(), status, null, errorMessage);
        AgentEvent stoppedEvent = saveStatusEvent(task, status, Map.of("message", eventMessage));
        eventBus.publishResult(stoppedEvent);
        return get(task.getTenantId(), task.getTaskId())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + task.getTaskId()));
    }

    private String terminalConfirmStatus(String taskStatus) {
        return switch (normalizeStatus(taskStatus)) {
            case "SUCCESS" -> "CONFIRMED";
            case "FAILED" -> "FAILED";
            case "REJECTED" -> "REJECTED";
            case "TIMEOUT_CANCELLED" -> "TIMEOUT_CANCELLED";
            case "KILLED" -> "KILLED";
            case "CANCELLED" -> "CANCELLED";
            default -> "EXPIRED";
        };
    }

    private String resolveToolName(InteractionResponse response, Map<String, Object> pendingToolExecution) {
        Object pendingToolName = pendingToolExecution == null ? null : pendingToolExecution.get("toolName");
        if (pendingToolName != null) {
            return String.valueOf(pendingToolName);
        }
        return response == null || response.getToolTraces() == null || response.getToolTraces().isEmpty()
            ? "unknown_tool"
            : firstText(response.getToolTraces().get(0).getToolName(), "unknown_tool");
    }

    @SuppressWarnings("unchecked")
    private String resolveConfirmationMessage(InteractionResponse response, Map<String, Object> pendingToolExecution) {
        Object confirmation = pendingToolExecution == null ? null : pendingToolExecution.get("confirmation");
        if (confirmation instanceof Map<?, ?> map) {
            Object purpose = firstPresent(map.get("purpose"), map.get("message"));
            if (purpose != null) {
                return String.valueOf(purpose);
            }
        }
        Object executionPlan = pendingToolExecution == null ? null : pendingToolExecution.get("executionPlan");
        if (executionPlan instanceof Map<?, ?> map) {
            Object purpose = firstPresent(map.get("purpose"), map.get("summary"));
            if (purpose != null) {
                return String.valueOf(purpose);
            }
        }
        String answer = response == null ? null : response.getAnswer();
        return firstText(answer, "Tool execution requires confirmation");
    }

    /**
     * Performs the pending tool execution operation.
     *
     * @param response the response value
     * @return the operation result
     */
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

    /**
     * Saves the status event.
     *
     * @param source the source value
     * @param status the status value
     * @param payload the payload value
     * @return the saved status event
     */
    private AgentEvent saveStatusEvent(AgentEvent source, String status, Map<String, Object> payload) {
        AgentEvent statusEvent = copyEvent(source, "STATUS", status, writePayload(payload));
        statusEvent.setParentEventId(source.getEventId());
        statusEvent.setSequence(nextSequence(source));
        eventStore.save(statusEvent);
        return statusEvent;
    }

    /**
     * Saves the status event.
     *
     * @param source the source value
     * @param status the status value
     * @param payload the payload value
     * @return the saved status event
     */
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

    private AgentEvent saveFeedbackEvent(AgentTaskLatestEntity source, String userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "Agent task feedback recorded");
        payload.put("useful", source.getFeedbackUseful());
        payload.put("adopted", source.getFeedbackAdopted());
        payload.put("resolved", source.getFeedbackResolved());
        payload.put("comment", source.getFeedbackComment());
        payload.put("reasonCategory", source.getFeedbackReasonCategory());
        payload.put("feedbackTime", source.getFeedbackTime());
        AgentEvent feedbackEvent = AgentEvent.builder()
            .taskId(source.getTaskId())
            .tenantId(source.getTenantId())
            .userId(firstText(userId, source.getUserId()))
            .agentId(source.getAgentId())
            .sessionId(source.getSessionId())
            .type("FEEDBACK")
            .status("RECORDED")
            .payload(writePayload(payload))
            .build();
        feedbackEvent.setSequence(nextSequence(source));
        eventStore.save(feedbackEvent);
        return feedbackEvent;
    }

    /**
     * Performs the emit runtime events operation.
     *
     * @param question the question value
     * @param response the response value
     * @param modelStartedAt the model started at value
     * @param modelFinishedAt the model finished at value
     */
    private void emitRuntimeEvents(AgentEvent question,
                                   InteractionResponse response,
                                   long modelStartedAt,
                                   long modelFinishedAt) {
        emitThinkEvent(question, response, modelStartedAt, modelFinishedAt);
        emitPlannerEvents(question, response);
        emitToolEvents(question, response);
    }

    /**
     * Performs the emit think event operation.
     *
     * @param question the question value
     * @param response the response value
     * @param modelStartedAt the model started at value
     * @param modelFinishedAt the model finished at value
     */
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

    /**
     * Performs the emit planner events operation.
     *
     * @param question the question value
     * @param response the response value
     */
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

    /**
     * Performs the emit tool events operation.
     *
     * @param question the question value
     * @param response the response value
     */
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

    /**
     * Performs the planner steps operation.
     *
     * @param response the response value
     * @return the operation result
     */
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

    /**
     * Copies the event.
     *
     * @param source the source value
     * @param type the type value
     * @param status the status value
     * @param payload the payload value
     * @return the operation result
     */
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

    /**
     * Updates the latest.
     *
     * @param taskId the task id value
     * @param status the status value
     * @param answerSummary the answer summary value
     * @param errorMessage the error message value
     */
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

    /**
     * Performs the queue question operation.
     *
     * @param latest the latest value
     * @param request the request value
     */
    private void queueQuestion(AgentTaskLatestEntity latest, AgentTaskSubmitRequest request) {
        queueQuestion(latest, request, true);
    }

    /**
     * Performs the queue question operation.
     *
     * @param latest the latest value
     * @param request the request value
     * @param persistQuestionEvent the persist question event value
     */
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

    /**
     * Loads the question payload.
     *
     * @param task the task value
     * @return the operation result
     */
    private AgentTaskPayload loadQuestionPayload(AgentTaskLatestEntity task) {
        return readPayload(loadQuestionEvent(task));
    }

    /**
     * Loads the question event.
     *
     * @param task the task value
     * @return the operation result
     */
    private AgentEvent loadQuestionEvent(AgentTaskLatestEntity task) {
        return eventStore.findFirstByTaskAndType(task.getTenantId(), task.getSessionId(), task.getTaskId(), "QUESTION")
            .orElseThrow(() -> new IllegalStateException("Question payload not found for task: " + task.getTaskId()));
    }

    /**
     * Reads the payload.
     *
     * @param event the event value
     * @return the operation result
     */
    private AgentTaskPayload readPayload(AgentEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), AgentTaskPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize agent task payload", ex);
        }
    }

    /**
     * Returns the task for tenant.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @return the task for tenant
     */
    private AgentTaskLatestEntity getTaskForTenant(String tenantId, String taskId) {
        String normalizedTenant = requireTenant(tenantId);
        AgentTaskLatestEntity task = latestRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!normalizedTenant.equals(task.getTenantId())) {
            throw new IllegalArgumentException("Task not found for tenant: " + taskId);
        }
        return task;
    }

    /**
     * Performs the next sequence operation.
     *
     * @param event the event value
     * @return the operation result
     */
    private long nextSequence(AgentEvent event) {
        return eventStore.nextSequence(event.getTenantId(), event.getSessionId(), event.getTaskId());
    }

    /**
     * Performs the next sequence operation.
     *
     * @param task the task value
     * @return the operation result
     */
    private long nextSequence(AgentTaskLatestEntity task) {
        return eventStore.nextSequence(task.getTenantId(), task.getSessionId(), task.getTaskId());
    }

    /**
     * Returns whether reconcile latest task state.
     *
     * @param task the task value
     * @return whether the condition is satisfied
     */
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
        } else if (List.of("CANCELLED", "REJECTED", "TIMEOUT_CANCELLED", "KILLED").contains(derivedStatus)) {
            errorMessage = extractErrorMessage(terminalEvent);
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = "Task " + derivedStatus.toLowerCase().replace('_', ' ');
            }
        }

        updateLatest(task.getTaskId(), derivedStatus, answerSummary, errorMessage);
        return true;
    }

    /**
     * Finds the latest terminal event.
     *
     * @param events the events value
     * @return the matching latest terminal event
     */
    private AgentEvent findLatestTerminalEvent(List<AgentEvent> events) {
        return events.stream()
            .filter(event -> TERMINAL_STATUSES.contains(normalizeStatus(event.getStatus())))
            .reduce((previous, current) -> current)
            .orElse(null);
    }

    /**
     * Finds the latest event.
     *
     * @param events the events value
     * @param type the type value
     * @return the matching latest event
     */
    private Optional<AgentEvent> findLatestEvent(List<AgentEvent> events, String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return events.stream()
            .filter(event -> type.equalsIgnoreCase(event.getType()))
            .reduce((previous, current) -> current);
    }

    /**
     * Performs the extract answer summary operation.
     *
     * @param event the event value
     * @return the operation result
     */
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

    /**
     * Performs the extract error message operation.
     *
     * @param event the event value
     * @return the operation result
     */
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

    /**
     * Resolves the answer time.
     *
     * @param response the response value
     * @param fallbackTime the fallback time value
     * @return the resolved answer time
     */
    private long resolveAnswerTime(InteractionResponse response, long fallbackTime) {
        if (response != null && response.getTimestamp() != null && response.getTimestamp() > 0) {
            return response.getTimestamp();
        }
        return fallbackTime;
    }

    /**
     * Performs the metadata value operation.
     *
     * @param metadata the metadata value
     * @param key the key value
     * @return the operation result
     */
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

    /**
     * Performs the long value operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
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

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private double percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return Math.round(((double) numerator * 10000D) / (double) denominator) / 100D;
    }

    private AgentEffectAnalytics.AgentMetric toAgentMetric(AgentTaskLatestRepository.AgentDiscoveryAggregate aggregate) {
        long totalTasks = longValue(aggregate.getTotalTasks());
        long failedTasks = longValue(aggregate.getFailedTasks());
        long feedbackTasks = longValue(aggregate.getFeedbackTasks());
        long usefulTasks = longValue(aggregate.getUsefulTasks());
        long adoptedTasks = longValue(aggregate.getAdoptedTasks());
        long resolvedTasks = longValue(aggregate.getResolvedTasks());
        return new AgentEffectAnalytics.AgentMetric(
            firstText(aggregate.getAgentId(), "default-agent"),
            totalTasks,
            longValue(aggregate.getSuccessTasks()),
            failedTasks,
            feedbackTasks,
            usefulTasks,
            adoptedTasks,
            resolvedTasks,
            percentage(usefulTasks, feedbackTasks),
            percentage(adoptedTasks, feedbackTasks),
            percentage(resolvedTasks, feedbackTasks),
            percentage(failedTasks, totalTasks)
        );
    }

    private String normalizeFeedbackReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        return switch (normalized) {
            case "answer_correct",
                 "steps_clear",
                 "tool_result_accurate",
                 "environment_mismatch",
                 "answer_incomplete",
                 "tool_call_error",
                 "knowledge_outdated",
                 "other" -> normalized;
            default -> "other";
        };
    }

    private String feedbackReasonLabel(String value) {
        String normalized = normalizeFeedbackReason(value);
        if (normalized == null) {
            return "其他";
        }
        return switch (normalized) {
            case "answer_correct" -> "答案正确";
            case "steps_clear" -> "步骤清晰";
            case "tool_result_accurate" -> "工具结果准确";
            case "environment_mismatch" -> "环境不匹配";
            case "answer_incomplete" -> "回答不完整";
            case "tool_call_error" -> "工具调用错误";
            case "knowledge_outdated" -> "知识库内容过期";
            default -> "其他";
        };
    }

    /**
     * Performs the string value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Returns whether is cancelled.
     *
     * @param taskId the task id value
     * @return whether the condition is satisfied
     */
    private boolean isCancelled(String taskId) {
        return cancellationRegistry.isCancelled(taskId) || latestRepository.findById(taskId)
            .map(AgentTaskLatestEntity::getStatus)
            .map(this::normalizeStatus)
            .filter(TERMINAL_STATUSES::contains)
            .isPresent();
    }

    /**
     * Returns whether requires confirmation.
     *
     * @param response the response value
     * @return whether the condition is satisfied
     */
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

    /**
     * Normalizes the normalize.
     *
     * @param request the request value
     * @return the operation result
     */
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

    /**
     * Validates the validate.
     *
     * @param request the request value
     */
    private void validate(AgentTaskSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Task request cannot be null");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
    }

    /**
     * Writes the payload.
     *
     * @param value the value value
     * @return the operation result
     */
    private String writePayload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize agent event payload", ex);
        }
    }

    /**
     * Performs the summarize operation.
     *
     * @param answer the answer value
     * @return the operation result
     */
    private String summarize(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        int maxLength = 500;
        return answer.length() <= maxLength ? answer : answer.substring(0, maxLength);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    /**
     * Normalizes the tenant.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    private String normalizeTenant(String tenantId) {
        return requireTenant(tenantId);
    }

    /**
     * Performs the require tenant operation.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    private String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        return tenantId.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /**
     * Normalizes the status.
     *
     * @param status the status value
     * @return the operation result
     */
    private String normalizeStatus(String status) {
        return firstText(status, "UNKNOWN").toUpperCase();
    }

    /**
     * Performs the sum operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private long sum(Collection<Long> values) {
        return values.stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Performs the first text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Object firstPresent(Object first, Object second) {
        return first == null ? second : first;
    }

    private static class AgentTaskStoppedException extends CancellationException {

        private final String status;

        private AgentTaskStoppedException(String status, String message) {
            super(message);
            this.status = status;
        }
    }
}
