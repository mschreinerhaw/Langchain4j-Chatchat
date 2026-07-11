package com.chatchat.chat.task;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
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
import java.util.ArrayList;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskService {

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL", "WAIT_CONFIRMATION", "WAITING_CONFIRM");
    private static final List<String> RECOVERABLE_STATUSES = List.of("PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL");
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCESS", "PARTIAL", "EMPTY", "FAILED", "CANCELLED", "REJECTED", "TIMEOUT_CANCELLED", "KILLED");
    private static final int MAX_IDLE_POLLS = 3;
    private static final int MAX_CONFIRMATION_ROUNDS = 20;
    private static final int DEBUG_TEXT_LIMIT = 8000;
    private static final int UI_CITATION_PREMISE_LIMIT = 900;
    private static final int UI_ANSWER_LIMIT = 6000;
    private static final Pattern JSON_FENCE_PATTERN = Pattern.compile("```json\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

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
    private final InterpretationPlanStore interpretationPlanStore;

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
        long waitConfirmationTasks = statusCounts.getOrDefault("WAIT_CONFIRMATION", 0L)
            + statusCounts.getOrDefault("WAITING_CONFIRM", 0L);
        long runningTasks = statusCounts.getOrDefault("RUNNING", 0L);
        long waitingTasks = waitToolTasks + waitModelTasks;
        long successTasks = statusCounts.getOrDefault("SUCCESS", 0L);
        long failedTasks = statusCounts.getOrDefault("FAILED", 0L);
        long cancelledTasks = statusCounts.getOrDefault("CANCELLED", 0L);
        long activeTasks = pendingTasks + runningTasks + waitingTasks + waitConfirmationTasks;
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
        List<AgentEvent> events = eventStore.listByTask(latestTask.getTenantId(), latestTask.getSessionId(), taskId, properties.getListLimit());
        Optional<AgentEvent> resultEvent = events.stream()
            .filter(event -> "ANSWER".equalsIgnoreCase(event.getType())
                || "RESULT".equalsIgnoreCase(event.getType())
                || "ERROR".equalsIgnoreCase(event.getType()))
            .reduce((previous, current) -> current);
        if (resultEvent.isPresent()) {
            return resultEvent;
        }
        return events.stream()
            .filter(event -> "COMPLETE".equalsIgnoreCase(event.getType())
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
                log.info("Agent task model inference started. taskId={} tenantId={} agentId={} sessionId={} mode={}",
                    question.getTaskId(),
                    question.getTenantId(),
                    question.getAgentId(),
                    question.getSessionId(),
                    interactionRequest.getMode());

                attachCancellationCheck(interactionRequest, question.getTaskId());
                InteractionResponse response = orchestrationService.chat(interactionRequest);
                long modelFinishedAt = System.currentTimeMillis();
                log.info("Agent task model inference finished. taskId={} tenantId={} durationMs={} answerPresent={} toolTraceCount={} responseMode={}",
                    question.getTaskId(),
                    question.getTenantId(),
                    modelFinishedAt - modelStartedAt,
                    response != null && response.getAnswer() != null && !response.getAnswer().isBlank(),
                    response == null || response.getToolTraces() == null ? 0 : response.getToolTraces().size(),
                    response == null ? null : response.getMode());
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
                    logAgentTaskEvent("confirmation", confirmationEvent);
                    updateLatest(question.getTaskId(), "WAIT_CONFIRMATION", null, "Agent task is waiting for MCP confirmation");
                    eventBus.publishResult(confirmationEvent);

                    AgentTaskSubmitRequest confirmationRequest = waitForMcpConfirmation(question, confirmation.getExpiredAt());
                    applyMcpConfirmation(interactionRequest, confirmationRequest, pendingToolExecution);
                    updateLatest(question.getTaskId(), "RUNNING", null, null);
                    saveStatusEvent(question, "RUNNING", Map.of("message", "Task resumed after MCP confirmation"));
                    continue;
                }

                ExecutionResultContract resultContract = compileExecutionResult(response);
                AgentEvent resultEvent = copyEvent(question, resultContract.eventType(), resultContract.status(), writePayload(resultContract.payload(response)));
                resultEvent.setSequence(nextSequence(question));
                resultEvent.setParentEventId(question.getEventId());
                resultEvent.setLatencyMs(response.getLatencyMs());
                resultEvent.setCreateTime(resolveAnswerTime(response, modelFinishedAt));
                eventStore.save(resultEvent);
                logAgentTaskEvent("result", resultEvent);
                Map<String, Object> completePayload = new LinkedHashMap<>();
                completePayload.put("message", resultContract.completeMessage());
                completePayload.put("mode", response.getMode());
                completePayload.put("handler", metadataValue(response.getMetadata(), "handler"));
                completePayload.put("toolTraceCount", response.getToolTraces() == null ? 0 : response.getToolTraces().size());
                completePayload.put("contractVersion", resultContract.contractVersion());
                completePayload.put("status", resultContract.status());
                completePayload.put("answer", resultContract.answerSummary());
                completePayload.put("citations", resultContract.citations());
                completePayload.put("evidencePremises", resultContract.evidencePremises());
                completePayload.put("confidence", resultContract.confidence());
                completePayload.put("evidenceSummary", resultContract.evidenceSummary());
                completePayload.put("visualization", resultContract.visualization());
                completePayload.put("uiResponse", resultContract.uiResponseView());
                completePayload.put("debug", resultContract.debugView());
                completePayload.put("executionResult", resultContract.asMap(response));
                AgentEvent completeEvent = copyEvent(question, "COMPLETE", resultContract.status(), writePayload(completePayload));
                completeEvent.setSequence(nextSequence(question));
                completeEvent.setParentEventId(resultEvent.getEventId());
                completeEvent.setCreateTime(Math.max(resultEvent.getCreateTime(), System.currentTimeMillis()));
                eventStore.save(completeEvent);
                logAgentTaskEvent("complete", completeEvent);
                updateLatest(question.getTaskId(), resultContract.status(), summarize(resultContract.answerSummary()), null);
                eventBus.publishResult(resultEvent);
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
                logAgentTaskEvent("cancelled", cancelledEvent);
                eventBus.publishResult(cancelledEvent);
            }
        } catch (Exception ex) {
            if (isCancelled(question.getTaskId())) {
                return;
            }
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            String errorCode = ex.getClass().getSimpleName();
            ExecutionResultContract resultContract = compileFailureResult(message, errorCode);
            Map<String, Object> errorPayload = new LinkedHashMap<>(resultContract.payload(null));
            errorPayload.put("error", message);
            errorPayload.put("errorCode", errorCode);
            AgentEvent errorEvent = copyEvent(question, "ERROR", "FAILED", writePayload(errorPayload));
            errorEvent.setSequence(nextSequence(question));
            errorEvent.setParentEventId(question.getEventId());
            errorEvent.setErrorCode(errorCode);
            eventStore.save(errorEvent);
            logAgentTaskEvent("error", errorEvent);
            Map<String, Object> completePayload = new LinkedHashMap<>(resultContract.asMap(null));
            completePayload.put("message", resultContract.completeMessage());
            completePayload.put("error", message);
            completePayload.put("errorCode", errorCode);
            completePayload.put("answer", resultContract.answerSummary());
            completePayload.put("uiResponse", resultContract.uiResponseView());
            AgentEvent completeEvent = copyEvent(question, "COMPLETE", "FAILED", writePayload(completePayload));
            completeEvent.setSequence(nextSequence(question));
            completeEvent.setParentEventId(errorEvent.getEventId());
            completeEvent.setErrorCode(errorCode);
            completeEvent.setCreateTime(Math.max(errorEvent.getCreateTime(), System.currentTimeMillis()));
            eventStore.save(completeEvent);
            logAgentTaskEvent("complete", completeEvent);
            updateLatest(question.getTaskId(), "FAILED", summarize(resultContract.answerSummary()), message);
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
        payload.putAll(planAttributionPayload(source));
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

    private Map<String, Object> planAttributionPayload(AgentTaskLatestEntity source) {
        if (source == null || source.getTaskId() == null || source.getTaskId().isBlank()) {
            return Map.of();
        }
        try {
            Optional<InterpretationPlanRecord> snapshot = interpretationPlanStore.getSnapshot(
                source.getTenantId(),
                source.getTaskId()
            );
            if (snapshot.isEmpty()) {
                return Map.of();
            }
            InterpretationPlanRecord record = snapshot.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("interpretationPlanId", record.planId());
            payload.put("interpretationPlanVersion", record.version());
            payload.put("interpretationPlanStatus", record.status());
            payload.put("interpretationPlanUpdatedAt", record.updatedAt());
            payload.put("interpretationPlanDagAvailable", record.dagJson() != null && !record.dagJson().isBlank());
            return payload;
        } catch (RuntimeException ex) {
            log.warn("Failed to attach InterpretationPlan attribution to feedback. taskId={} error={}",
                source.getTaskId(), ex.getMessage());
            return Map.of();
        }
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
        logAgentTaskEvent("think", thinkEvent);
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
            logAgentTaskEvent("plan", planEvent);

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
                logAgentTaskEvent("planner_think", thinkEvent);
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
            logAgentTaskEvent("wait_tool", waitToolEvent);

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
            logAgentTaskEvent("tool_call", toolCallEvent);

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
            logAgentTaskEvent("tool_result", toolResultEvent);
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

    private void logAgentTaskEvent(String phase, AgentEvent event) {
        if (event == null) {
            return;
        }
        log.info("agentTaskStepLog phase={} taskId={} tenantId={} sessionId={} sequence={} type={} status={} toolName={} latencyMs={} payload=\n{}",
            phase,
            event.getTaskId(),
            event.getTenantId(),
            event.getSessionId(),
            event.getSequence(),
            event.getType(),
            event.getStatus(),
            event.getToolName(),
            event.getLatencyMs(),
            event.getPayload() == null ? "" : event.getPayload());
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

    private ExecutionResultContract compileExecutionResult(InteractionResponse response) {
        String answer = response == null ? "" : firstText(response.getAnswer(), "");
        Map<String, Object> metadata = response == null ? Map.of() : asStringMap(response.getMetadata());
        Map<String, Object> agentMetadata = asStringMap(metadata.get("agent"));
        boolean fatalExecutionBlocked = booleanValue(firstPresent(
            agentMetadata.get("fatalExecutionBlocked"),
            agentMetadata.get("mandatoryWorkflowBlocked")
        ));
        boolean hasAnswer = !answer.isBlank();
        boolean hasSources = response != null && response.getSources() != null && !response.getSources().isEmpty();
        boolean hasToolOutput = response != null && response.getToolTraces() != null && !response.getToolTraces().isEmpty();
        boolean hasObservations = metadataList(response == null ? null : response.getMetadata(), "observations");
        boolean hasArtifact = hasAnswer || hasSources || hasToolOutput || hasObservations;
        String status = fatalExecutionBlocked ? "FAILED" : (hasAnswer ? "SUCCESS" : (hasArtifact ? "PARTIAL" : "EMPTY"));
        String message = switch (status) {
            case "SUCCESS" -> "Agent task completed";
            case "FAILED" -> "Agent task failed before required tool workflow completed";
            case "PARTIAL" -> "Agent task completed with partial result";
            case "EMPTY" -> "Agent task completed without displayable result";
            default -> "Agent task completed";
        };
        String displayAnswer = switch (status) {
            case "SUCCESS" -> answer;
            case "FAILED" -> firstText(answer, firstTextValue(agentMetadata.get("errorMessage"), "必需工具未完成，已阻断最终回答。请检查工具调用失败原因后重试。"));
            case "PARTIAL" -> "本次执行已完成，并获取到部分工具结果或中间产物，但没有生成最终回答。请查看执行步骤、引用来源或工具轨迹，必要时补充要求后重试。";
            case "EMPTY" -> "本次执行已结束，但没有产生可展示的回答或结果产物。请检查 Agent 配置、模型服务，或换一种问法后重试。";
            default -> answer;
        };
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("hasAnswer", hasAnswer);
        flags.put("hasInsight", hasAnswer && !fatalExecutionBlocked);
        flags.put("hasToolOutput", hasToolOutput);
        flags.put("hasSources", hasSources);
        flags.put("hasArtifact", hasArtifact);
        Map<String, Object> reasoningPayload = evidenceReasoningPayload(response, answer);
        UiResponseContract uiResponse = uiResponse(status, displayAnswer, response, reasoningPayload);
        Map<String, Object> debug = debugPayload(response, reasoningPayload);
        return new ExecutionResultContract(status, message, flags, uiResponse, debug);
    }

    private ExecutionResultContract compileFailureResult(String message, String errorCode) {
        String normalizedError = firstText(message, "Agent task failed");
        String normalizedCode = firstText(errorCode, "AGENT_TASK_FAILED");
        String answer = """
            本次执行失败，但失败信息已被捕获并返回。

            失败类型：%s
            失败原因：%s

            已将该失败作为本次任务结果反馈给用户端。请根据工具执行日志、权限配置、参数绑定或模型服务状态继续排查。
            """.formatted(normalizedCode, normalizedError).trim();
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("hasAnswer", true);
        flags.put("hasInsight", false);
        flags.put("hasToolOutput", false);
        flags.put("hasSources", false);
        flags.put("hasArtifact", true);
        UiResponseContract uiResponse = new UiResponseContract(
            "ui_response_v1",
            "FAILED",
            answer,
            List.of(),
            List.of(),
            null,
            "",
            Map.of("type", "none"),
            null
        );
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("errorCode", normalizedCode);
        debug.put("errorMessage", normalizedError);
        return new ExecutionResultContract(
            "FAILED",
            "Agent task failed with captured error details",
            flags,
            uiResponse,
            debug
        );
    }

    private UiResponseContract uiResponse(String status,
                                          String fallbackAnswer,
                                          InteractionResponse response,
                                          Map<String, Object> reasoningPayload) {
        Map<String, Object> result = asStringMap(reasoningPayload.get("result"));
        Object visualizationSpec = ExecutionResultContract.visualizationSpec(response == null ? null : response.getMetadata());
        String evidenceSummary = firstTextValue(
            result.get("evidenceSummary"),
            reasoningPayload.get("evidenceSummary"),
            ""
        );
        List<Map<String, Object>> citations = citations(response, reasoningPayload);
        List<Map<String, Object>> evidencePremises = evidencePremises(citations);
        Double confidence = firstDouble(
            result.get("confidence"),
            valueAt(reasoningPayload, "reasoningTrace", "pathDecision", "pathCoherence")
        );
        String answer = firstTextValue(
            structuredUiAnswer(fallbackAnswer),
            plainAnswer(fallbackAnswer),
            fallbackAnswer,
            result.get("answer"),
            result.get("conclusion")
        );
        Map<String, Object> visualization = visualization(visualizationSpec, reasoningPayload);
        return new UiResponseContract(
            "ui_response_v1",
            status,
            firstText(cleanDisplayAnswer(answer), ""),
            citations,
            evidencePremises,
            confidence,
            evidenceSummary == null ? "" : evidenceSummary,
            visualization,
            visualizationSpec
        );
    }

    private List<Map<String, Object>> evidencePremises(List<Map<String, Object>> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> premises = new ArrayList<>();
        for (Map<String, Object> citation : citations) {
            String text = compactCitationText(firstTextValue(citation.get("text"), ""));
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> premise = new LinkedHashMap<>();
            premise.put("rank", premises.size() + 1);
            premise.put("text", text);
            premises.add(premise);
            if (premises.size() >= 6) {
                break;
            }
        }
        return List.copyOf(premises);
    }

    private String compactCitationText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= UI_CITATION_PREMISE_LIMIT ? text : text.substring(0, UI_CITATION_PREMISE_LIMIT) + "...";
    }

    private boolean isProtocolLikeAnswer(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        String lower = text.toLowerCase();
        return ((text.startsWith("{") || text.startsWith("[") || lower.startsWith("```json"))
            && (lower.contains("\"uiresponse\"")
            || lower.contains("\"executionresult\"")
            || lower.contains("\"finalanswer\"")
            || lower.contains("\"reasoningtrace\"")
            || lower.contains("\"trustedsql\"")
            || lower.contains("\"deterministicfacts\"")))
            || lower.contains("evidence_reasoning_v2")
            || lower.contains("reasoningpayload:")
            || lower.contains("lockedanswer:")
            || lower.contains("deterministic answer lock")
            || lower.contains("evidence_execution_contract");
    }

    private String structuredUiAnswer(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank() || !isProtocolLikeAnswer(rawAnswer)) {
            return "";
        }
        String json = extractJsonObject(rawAnswer);
        if (json.isBlank()) {
            return "";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            Map<String, Object> uiResponse = asStringMap(root.get("uiResponse"));
            Map<String, Object> nestedExecution = asStringMap(root.get("executionResult"));
            Map<String, Object> nestedUiResponse = asStringMap(nestedExecution.get("uiResponse"));
            return firstTextValue(
                uiResponse.get("answer"),
                nestedUiResponse.get("answer"),
                root.get("answer"),
                ""
            );
        } catch (Exception ex) {
            log.debug("Failed to extract structured ui answer", ex);
            return "";
        }
    }

    private String extractJsonObject(String rawAnswer) {
        String text = rawAnswer == null ? "" : rawAnswer.trim();
        Matcher fenceMatcher = JSON_FENCE_PATTERN.matcher(text);
        while (fenceMatcher.find()) {
            String body = fenceMatcher.group(1);
            if (body != null && (body.contains("uiResponse") || body.contains("executionResult"))) {
                return body.trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1).trim();
        }
        return "";
    }

    static String cleanDisplayAnswer(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = JSON_FENCE_PATTERN.matcher(value).replaceAll("").trim();
        text = text.replaceAll("(?is)reasoningPayload:\\s*```json\\s*.*?\\s*```", "").trim();
        return text.length() <= UI_ANSWER_LIMIT ? text : text.substring(0, UI_ANSWER_LIMIT);
    }

    private Map<String, Object> debugPayload(InteractionResponse response, Map<String, Object> reasoningPayload) {
        Map<String, Object> debug = new LinkedHashMap<>();
        if (reasoningPayload != null && !reasoningPayload.isEmpty()) {
            putIfPresent(debug, "executionSpec", reasoningPayload.get("executionSpec"));
            putIfPresent(debug, "evidence", reasoningPayload.get("evidence"));
            putIfPresent(debug, "executionDag", reasoningPayload.get("executionDag"));
            putIfPresent(debug, "reasoningTrace", reasoningPayload.get("reasoningTrace"));
            putIfPresent(debug, "trustedSql", reasoningPayload.get("trustedSql"));
            putIfPresent(debug, "deterministicFacts", reasoningPayload.get("deterministicFacts"));
            putIfPresent(debug, "result", reasoningPayload.get("result"));
            putIfPresent(debug, "contractHash", reasoningPayload.get("contractHash"));
            putIfPresent(debug, "graphViewHash", reasoningPayload.get("graphViewHash"));
            putIfPresent(debug, "pathState", reasoningPayload.get("pathState"));
            putIfPresent(debug, "decision", reasoningPayload.get("decision"));
        }
        Map<String, Object> metadata = response == null ? Map.of() : response.getMetadata();
        Object agent = metadata == null ? null : metadata.get("agent");
        if (agent instanceof Map<?, ?> agentMap) {
            Map<String, Object> agentDebug = new LinkedHashMap<>();
            putIfPresent(agentDebug, "plannerSteps", agentMap.get("plannerSteps"));
            putIfPresent(agentDebug, "observations", agentMap.get("observations"));
            putIfPresent(agentDebug, "events", agentMap.get("events"));
            if (!agentDebug.isEmpty()) {
                debug.put("agent", agentDebug);
            }
        }
        return debug;
    }

    private Map<String, Object> evidenceReasoningPayload(InteractionResponse response, String answer) {
        Map<String, Object> fromAnswer = evidenceReasoningPayload(answer);
        if (!fromAnswer.isEmpty()) {
            return fromAnswer;
        }
        Map<String, Object> metadata = response == null ? Map.of() : response.getMetadata();
        Map<String, Object> fromObservations = evidenceReasoningPayloadFromObservations(metadata == null ? null : metadata.get("observations"));
        if (!fromObservations.isEmpty()) {
            return fromObservations;
        }
        Object agent = metadata == null ? null : metadata.get("agent");
        if (agent instanceof Map<?, ?> agentMap) {
            return evidenceReasoningPayloadFromObservations(agentMap.get("observations"));
        }
        return Map.of();
    }

    private Map<String, Object> evidenceReasoningPayloadFromObservations(Object observations) {
        if (!(observations instanceof List<?> values)) {
            return Map.of();
        }
        for (Object value : values) {
            Map<String, Object> payload = evidenceReasoningPayload(value == null ? "" : String.valueOf(value));
            if (!payload.isEmpty()) {
                return payload;
            }
        }
        return Map.of();
    }

    private Map<String, Object> evidenceReasoningPayload(String answer) {
        if (answer == null || answer.isBlank()) {
            return Map.of();
        }
        for (String candidate : jsonCandidates(answer)) {
            if (!candidate.contains("evidence_reasoning_v2")
                && !candidate.contains("\"executionDag\"")
                && !candidate.contains("\"reasoningTrace\"")) {
                continue;
            }
            try {
                Object parsed = objectMapper.readValue(candidate, Object.class);
                Map<String, Object> map = asStringMap(parsed);
                if (!map.isEmpty()) {
                    return map;
                }
            } catch (Exception ignored) {
                // Keep scanning other candidates.
            }
        }
        return Map.of();
    }

    private List<String> jsonCandidates(String answer) {
        List<String> candidates = new ArrayList<>();
        Matcher matcher = JSON_FENCE_PATTERN.matcher(answer);
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body != null && !body.isBlank()) {
                candidates.add(body.trim());
            }
        }
        String balanced = balancedJson(answer);
        if (balanced != null && !balanced.isBlank()) {
            candidates.add(balanced);
        }
        String trimmed = answer.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            candidates.add(trimmed);
        }
        return candidates.stream().distinct().toList();
    }

    private String balancedJson(String text) {
        int marker = text.indexOf("\"type\"");
        int typeMarker = text.indexOf("evidence_reasoning_v2");
        int anchor = marker >= 0 ? marker : typeMarker;
        if (anchor < 0) {
            return null;
        }
        int start = text.lastIndexOf('{', anchor);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, index + 1).trim();
                }
            }
        }
        return null;
    }

    private String plainAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String stripped = JSON_FENCE_PATTERN.matcher(answer).replaceAll("").trim();
        if (stripped.isBlank()) {
            return "";
        }
        return stripped.length() <= DEBUG_TEXT_LIMIT ? stripped : stripped.substring(0, DEBUG_TEXT_LIMIT);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> citations(InteractionResponse response, Map<String, Object> reasoningPayload) {
        List<Map<String, Object>> values = new ArrayList<>();
        addEvidenceCitations(values, asStringMap(reasoningPayload.get("evidence")).get("direct"), "direct");
        addEvidenceCitations(values, asStringMap(reasoningPayload.get("evidence")).get("supporting"), "supporting");
        addEvidenceCitations(values, asStringMap(reasoningPayload.get("evidence")).get("context"), "context");
        if (response != null && response.getSources() != null) {
            int rank = values.size() + 1;
            for (Object source : response.getSources()) {
                Map<String, Object> map = objectMapper.convertValue(source, Map.class);
                Map<String, Object> citation = new LinkedHashMap<>();
                citation.put("rank", rank++);
                citation.put("sourceRef", firstTextValue(map.get("source"), map.get("docId"), map.get("url"), ""));
                citation.put("title", firstTextValue(map.get("title"), map.get("source"), ""));
                citation.put("text", firstTextValue(map.get("snippet"), map.get("content"), ""));
                values.add(citation);
            }
        }
        return dedupeCitations(values).stream().limit(12).toList();
    }

    private void addEvidenceCitations(List<Map<String, Object>> values, Object rawItems, String tier) {
        if (!(rawItems instanceof List<?> items)) {
            return;
        }
        for (Object raw : items) {
            Map<String, Object> item = asStringMap(raw);
            if (item.isEmpty()) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("rank", values.size() + 1);
            citation.put("sourceRef", firstTextValue(item.get("refId"), item.get("source"), ""));
            citation.put("title", firstTextValue(item.get("title"), item.get("type"), tier));
            citation.put("text", firstTextValue(item.get("text"), item.get("content"), item.get("summary"), ""));
            citation.put("confidence", firstDouble(item.get("confidence")));
            citation.put("tier", tier);
            values.add(citation);
        }
    }

    private List<Map<String, Object>> dedupeCitations(List<Map<String, Object>> citations) {
        List<Map<String, Object>> values = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> citation : citations) {
            String key = firstTextValue(citation.get("sourceRef"), "") + "|" + firstTextValue(citation.get("text"), "");
            if (!seen.contains(key)) {
                seen.add(key);
                values.add(citation);
            }
        }
        return values;
    }

    private Map<String, Object> visualization(Object visualizationSpec, Map<String, Object> reasoningPayload) {
        Map<String, Object> visualization = new LinkedHashMap<>();
        if (visualizationSpec instanceof Map<?, ?> spec) {
            visualization.put("type", firstTextValue(spec.get("type"), "table"));
            visualization.put("spec", visualizationSpec);
            return visualization;
        }
        Map<String, Object> dag = asStringMap(reasoningPayload.get("executionDag"));
        if (dag.get("nodes") instanceof List<?> nodes && !nodes.isEmpty()) {
            visualization.put("type", "graph");
            visualization.put("available", true);
            return visualization;
        }
        visualization.put("type", "none");
        return visualization;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Collection<?> collection && collection.isEmpty()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private Object valueAt(Map<String, Object> source, String... path) {
        Object current = source;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private Map<String, Object> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    private Double firstDouble(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                return number.doubleValue();
            }
            if (value != null) {
                try {
                    double parsed = Double.parseDouble(String.valueOf(value));
                    if (Double.isFinite(parsed)) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return null;
    }

    private String firstTextValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean metadataList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return false;
        }
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        Object agent = metadata.get("agent");
        if (agent instanceof Map<?, ?> agentMap) {
            Object nested = agentMap.get(key);
            return nested instanceof List<?> list && !list.isEmpty();
        }
        return false;
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

    private record ExecutionResultContract(
        String status,
        String completeMessage,
        Map<String, Object> semanticFlags,
        UiResponseContract uiResponse,
        Map<String, Object> debug
    ) {

        private static final int MAX_VISUALIZATION_BLOCKS = 6;

        private String eventType() {
            return "SUCCESS".equals(status) ? "ANSWER" : "RESULT";
        }

        private String answerSummary() {
            return uiResponse == null ? "" : uiResponse.answer();
        }

        private String contractVersion() {
            return uiResponse == null ? "ui_response_v1" : uiResponse.contractVersion();
        }

        private List<Map<String, Object>> citations() {
            return uiResponse == null ? List.of() : uiResponse.citations();
        }

        private List<Map<String, Object>> evidencePremises() {
            return uiResponse == null ? List.of() : uiResponse.evidencePremises();
        }

        private Double confidence() {
            return uiResponse == null ? null : uiResponse.confidence();
        }

        private String evidenceSummary() {
            return uiResponse == null ? "" : uiResponse.evidenceSummary();
        }

        private Map<String, Object> visualization() {
            return uiResponse == null ? Map.of("type", "none") : uiResponse.visualization();
        }

        private Map<String, Object> debugView() {
            return debug == null ? Map.of() : debug;
        }

        private Map<String, Object> uiResponseView() {
            return uiResponse == null ? Map.of() : uiResponse.asMap();
        }

        private Map<String, Object> payload(InteractionResponse response) {
            Map<String, Object> metadata = safeMetadata(response == null ? null : response.getMetadata());
            Map<String, Object> result = asMap(response);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", contractVersion());
            payload.put("conversationId", response == null ? "" : response.getConversationId());
            payload.put("requestId", response == null ? "" : response.getRequestId());
            payload.put("mode", response == null ? "" : response.getMode());
            payload.put("status", status);
            payload.put("answer", answerSummary());
            payload.put("citations", citations());
            payload.put("evidencePremises", evidencePremises());
            payload.put("confidence", confidence());
            payload.put("evidenceSummary", evidenceSummary());
            payload.put("visualization", visualization());
            Object visualizationSpec = uiResponse == null ? null : uiResponse.visualizationSpec();
            if (visualizationSpec != null) {
                payload.put("visualizationSpec", visualizationSpec);
            }
            payload.put("sources", response == null || response.getSources() == null ? List.of() : response.getSources());
            payload.put("toolTraces", response == null || response.getToolTraces() == null ? List.of() : response.getToolTraces());
            payload.put("metadata", metadata);
            payload.put("latencyMs", response == null ? null : response.getLatencyMs());
            payload.put("timestamp", response == null || response.getTimestamp() == null ? System.currentTimeMillis() : response.getTimestamp());
            payload.put("uiResponse", uiResponseView());
            payload.put("executionResult", result);
            payload.put("debug", debugView());
            return payload;
        }

        private Map<String, Object> asMap() {
            return asMap(null);
        }

        private Map<String, Object> asMap(InteractionResponse response) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("uiResponse", uiResponseView());
            result.put("semanticFlags", semanticFlags == null ? Map.of() : semanticFlags);
            result.put("message", completeMessage);
            result.put("traceSummary", Map.of(
                "sourceCount", response == null || response.getSources() == null ? 0 : response.getSources().size(),
                "toolTraceCount", response == null || response.getToolTraces() == null ? 0 : response.getToolTraces().size()
            ));
            result.put("debug", debugView());
            return result;
        }

        private static Map<String, Object> safeMetadata(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            copyMetadataValue(safe, metadata, "availableTools");
            copyMetadataValue(safe, metadata, "requiredTools");
            copyMetadataValue(safe, metadata, "toolIntents");
            copyMetadataValue(safe, metadata, "handler");
            copyMetadataValue(safe, metadata, "skillId");
            copyMetadataValue(safe, metadata, "modelName");
            copyMetadataValue(safe, metadata, "historyUsed");
            copyMetadataValue(safe, metadata, "summaryUsed");
            copyMetadataValue(safe, metadata, "experienceHintsUsed");
            return safe;
        }

        private static void copyMetadataValue(Map<String, Object> target, Map<String, Object> metadata, String key) {
            if (metadata.containsKey(key)) {
                target.put(key, metadata.get(key));
            }
        }

        private static Object visualizationSpec(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return null;
            }
            Object direct = metadata.get("visualizationSpec");
            if (direct != null) {
                return normalizeVisualizationSpec(direct);
            }
            direct = metadata.get("dataVisualization");
            if (direct != null) {
                return normalizeVisualizationSpec(direct);
            }
            Object agent = metadata.get("agent");
            if (agent instanceof Map<?, ?> agentMap) {
                Object nested = agentMap.get("visualizationSpec");
                return normalizeVisualizationSpec(nested == null ? agentMap.get("dataVisualization") : nested);
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static Object normalizeVisualizationSpec(Object raw) {
            if (!(raw instanceof Map<?, ?> rawMap)) {
                return null;
            }
            Object nested = firstPresent(rawMap.get("visualizationSpec"), rawMap.get("dataVisualization"));
            if (nested instanceof Map<?, ?>) {
                return normalizeVisualizationSpec(nested);
            }
            Map<String, Object> spec = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                if (key != null) {
                    spec.put(String.valueOf(key), value);
                }
            });

            String requestedType = firstTextValue(spec.get("type"), "").toLowerCase();
            if (spec.get("blocks") instanceof List<?> || "panel".equals(requestedType) || "dashboard".equals(requestedType)) {
                return normalizeVisualizationPanel(spec);
            }

            List<Map<String, Object>> rows = rows(spec);
            if (rows.isEmpty()) {
                rows = metricRows(spec);
            }
            if (rows.isEmpty()) {
                return null;
            }

            List<String> columns = columns(rows);
            Map<String, Object> dataset = spec.get("dataset") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            String xKey = firstTextValue(dataset.get("xKey"), spec.get("xKey"), spec.get("x"), firstNonNumericColumn(columns, rows), columns.isEmpty() ? "name" : columns.get(0));
            List<Map<String, Object>> series = series(spec, dataset, columns, rows, xKey);
            String type = normalizeVisualizationType(String.valueOf(spec.getOrDefault("type", "")), rows, series);
            String chartType = "chart".equals(type) ? chooseChartType(spec, rows, xKey, series) : "";

            Map<String, Object> nextDataset = new LinkedHashMap<>();
            nextDataset.put("xKey", xKey);
            nextDataset.put("series", series);
            nextDataset.put("rows", rows);

            Map<String, Object> ui = new LinkedHashMap<>();
            Object rawUi = spec.get("ui");
            if (rawUi instanceof Map<?, ?> uiMap) {
                ui.put("allowSwitch", !Boolean.FALSE.equals(uiMap.get("allowSwitch")));
                ui.put("defaultView", firstTextValue(uiMap.get("defaultView"), "table".equals(type) ? "table" : "chart"));
            } else {
                ui.put("allowSwitch", true);
                ui.put("defaultView", "table".equals(type) ? "table" : "chart");
            }

            Map<String, Object> insight = new LinkedHashMap<>();
            Object rawInsight = spec.get("insight");
            if (rawInsight instanceof Map<?, ?> insightMap) {
                insight.put("summary", firstTextValue(insightMap.get("summary"), ""));
                insight.put("anomaly", firstTextValue(insightMap.get("anomaly"), ""));
                insight.put("trend", firstTextValue(insightMap.get("trend"), ""));
                insight.put("drivers", insightMap.get("drivers") instanceof List<?> drivers ? drivers : List.of());
            } else {
                insight.put("summary", firstTextValue(spec.get("summary"), ""));
                insight.put("anomaly", firstTextValue(spec.get("anomaly"), ""));
                insight.put("trend", firstTextValue(spec.get("trend"), ""));
                insight.put("drivers", List.of());
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("version", "v1");
            normalized.put("type", type);
            normalized.put("chartType", chartType);
            normalized.put("title", firstTextValue(spec.get("title"), spec.get("name"), "metric".equals(type) ? "Key Metrics" : "Auto Visualization"));
            normalized.put("analysisType", firstTextValue(spec.get("analysisType"), ""));
            normalized.put("dataset", nextDataset);
            normalized.put("ui", ui);
            normalized.put("insight", insight);
            normalized.put("analysisResult", analysisResult(spec, insight));
            normalized.put("insightSpec", insight);
            return normalized;
        }

        @SuppressWarnings("unchecked")
        private static Object normalizeVisualizationPanel(Map<String, Object> spec) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            Object rawBlocks = spec.get("blocks");
            if (rawBlocks instanceof List<?> list) {
                int index = 1;
                for (Object item : list) {
                    if (blocks.size() >= MAX_VISUALIZATION_BLOCKS) {
                        break;
                    }
                    if (item instanceof Map<?, ?> blockMap) {
                        Map<String, Object> block = new LinkedHashMap<>();
                        blockMap.forEach((key, value) -> {
                            if (key != null) {
                                block.put(String.valueOf(key), value);
                            }
                        });
                        Object rawSpec = firstPresent(block.get("spec"), firstPresent(block.get("data"), block.get("visualizationSpec")));
                        if (!(rawSpec instanceof Map<?, ?>)) {
                            rawSpec = block;
                        }
                        Map<String, Object> rawBlockSpec = new LinkedHashMap<>();
                        ((Map<?, ?>) rawSpec).forEach((key, value) -> {
                            if (key != null) {
                                rawBlockSpec.put(String.valueOf(key), value);
                            }
                        });
                        rawBlockSpec.putIfAbsent("type", block.get("type"));
                        rawBlockSpec.putIfAbsent("title", block.get("title"));
                        Object normalizedSpec = normalizeVisualizationSpec(rawBlockSpec);
                        if (normalizedSpec instanceof Map<?, ?> normalizedMap && !"panel".equals(String.valueOf(normalizedMap.get("type")))) {
                            Map<String, Object> normalizedBlock = new LinkedHashMap<>();
                            normalizedBlock.put("id", firstTextValue(block.get("id"), "block-" + index));
                            normalizedBlock.put("type", firstTextValue(block.get("type"), normalizedMap.get("type"), "chart"));
                            normalizedBlock.put("title", firstTextValue(block.get("title"), normalizedMap.get("title"), "Block " + index));
                            normalizedBlock.put("spec", normalizedSpec);
                            blocks.add(normalizedBlock);
                        }
                    }
                    index++;
                }
            }
            if (blocks.isEmpty()) {
                Map<String, Object> fallbackSource = new LinkedHashMap<>(spec);
                fallbackSource.remove("blocks");
                fallbackSource.put("type", firstTextValue(spec.get("blockType"), "chart"));
                Object fallback = normalizeVisualizationSpec(fallbackSource);
                if (fallback instanceof Map<?, ?> fallbackMap && !"panel".equals(String.valueOf(fallbackMap.get("type")))) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("id", "primary");
                    block.put("type", fallbackMap.get("type"));
                    block.put("title", fallbackMap.get("title"));
                    block.put("spec", fallback);
                    blocks.add(block);
                }
            }
            if (blocks.isEmpty()) {
                return null;
            }

            Map<String, Object> insight = new LinkedHashMap<>();
            Object rawInsight = spec.get("insight");
            if (rawInsight instanceof Map<?, ?> insightMap) {
                insight.put("summary", firstTextValue(insightMap.get("summary"), ""));
                insight.put("anomaly", firstTextValue(insightMap.get("anomaly"), ""));
                insight.put("trend", firstTextValue(insightMap.get("trend"), ""));
                insight.put("drivers", insightMap.get("drivers") instanceof List<?> drivers ? drivers : List.of());
            } else {
                insight.put("summary", firstTextValue(spec.get("summary"), ""));
                insight.put("anomaly", firstTextValue(spec.get("anomaly"), ""));
                insight.put("trend", firstTextValue(spec.get("trend"), ""));
                insight.put("drivers", List.of());
            }

            String layout = firstTextValue(spec.get("layout"), "stack").toLowerCase();
            if (!List.of("grid", "stack").contains(layout)) {
                layout = "stack";
            }
            Map<String, Object> ui = new LinkedHashMap<>();
            Object rawUi = spec.get("ui");
            if (rawUi instanceof Map<?, ?> uiMap) {
                ui.put("allowSwitch", !Boolean.FALSE.equals(uiMap.get("allowSwitch")));
                ui.put("defaultView", firstTextValue(uiMap.get("defaultView"), "panel"));
            } else {
                ui.put("allowSwitch", true);
                ui.put("defaultView", "panel");
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("version", "v2");
            normalized.put("type", "panel");
            normalized.put("title", firstTextValue(spec.get("title"), spec.get("name"), "BI Panel"));
            normalized.put("analysisType", firstTextValue(spec.get("analysisType"), ""));
            normalized.put("layout", layout);
            normalized.put("blocks", blocks);
            normalized.put("ui", ui);
            normalized.put("insight", insight);
            normalized.put("analysisResult", analysisResult(spec, insight));
            normalized.put("insightSpec", insight);
            return normalized;
        }

        private static Map<String, Object> analysisResult(Map<String, Object> spec, Map<String, Object> insight) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", firstTextValue(spec.get("analysisType"), ""));
            result.put("summary", firstTextValue(insight.get("summary"), ""));
            result.put("drivers", insight.get("drivers") instanceof List<?> drivers ? drivers : List.of());
            return result;
        }

        private static List<Map<String, Object>> rows(Map<String, Object> spec) {
            Object dataset = spec.get("dataset");
            if (dataset instanceof Map<?, ?> datasetMap && datasetMap.get("rows") instanceof List<?> datasetRows) {
                return rowMaps(datasetRows, datasetMap.get("columns"));
            }
            Object data = spec.get("data");
            return data instanceof List<?> list ? rowMaps(list, null) : List.of();
        }

        private static List<Map<String, Object>> metricRows(Map<String, Object> spec) {
            Object metrics = firstPresent(spec.get("metrics"), firstPresent(spec.get("values"), spec.get("kpis")));
            if (metrics instanceof Map<?, ?> map) {
                List<Map<String, Object>> rows = new ArrayList<>();
                map.forEach((key, value) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("metric", String.valueOf(key));
                    row.put("value", value);
                    rows.add(row);
                });
                return rows;
            }
            if (metrics instanceof List<?> list) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int index = 1;
                for (Object item : list) {
                    if (item instanceof Map<?, ?> metric) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("metric", firstTextValue(metric.get("label"), metric.get("name"), metric.get("key"), "Metric " + index));
                        row.put("value", firstPresent(metric.get("value"), metric.get("amount")));
                        row.put("unit", firstTextValue(metric.get("unit"), ""));
                        rows.add(row);
                    }
                    index++;
                }
                return rows;
            }
            return List.of();
        }

        private static List<Map<String, Object>> rowMaps(List<?> values, Object columnsValue) {
            List<String> columns = columnsValue instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((key, item) -> {
                        if (key != null) {
                            row.put(String.valueOf(key), item);
                        }
                    });
                    rows.add(row);
                } else if (value instanceof List<?> rowValues && !columns.isEmpty()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size() && i < rowValues.size(); i++) {
                        row.put(columns.get(i), rowValues.get(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        }

        private static List<String> columns(List<Map<String, Object>> rows) {
            List<String> columns = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                for (String key : row.keySet()) {
                    if (!columns.contains(key)) {
                        columns.add(key);
                    }
                }
            }
            return columns;
        }

        private static List<Map<String, Object>> series(Map<String, Object> spec,
                                                 Map<String, Object> dataset,
                                                 List<String> columns,
                                                 List<Map<String, Object>> rows,
                                                 String xKey) {
            Object datasetSeries = dataset.get("series");
            List<Map<String, Object>> values = new ArrayList<>();
            if (datasetSeries instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        String yKey = firstTextValue(map.get("yKey"), "");
                        if (!yKey.isBlank() && hasNumeric(rows, yKey)) {
                            values.add(Map.of("name", firstTextValue(map.get("name"), yKey), "yKey", yKey));
                        }
                    }
                }
            }
            if (values.isEmpty()) {
                Object y = spec.get("y");
                List<?> yKeys = y instanceof List<?> list ? list : (y == null ? List.of() : List.of(y));
                for (Object item : yKeys) {
                    String yKey = String.valueOf(item);
                    if (!yKey.isBlank() && hasNumeric(rows, yKey)) {
                        values.add(Map.of("name", yKey, "yKey", yKey));
                    }
                }
            }
            if (values.isEmpty()) {
                for (String column : columns) {
                    if (!column.equals(xKey) && hasNumeric(rows, column)) {
                        values.add(Map.of("name", column, "yKey", column));
                    }
                    if (values.size() >= 4) {
                        break;
                    }
                }
            }
            return values;
        }

        private static String normalizeVisualizationType(String type, List<Map<String, Object>> rows, List<Map<String, Object>> series) {
            String normalized = type == null ? "" : type.toLowerCase();
            if ("metrics".equals(normalized)) {
                return "metric";
            }
            if ("metric".equals(normalized) || "table".equals(normalized)) {
                return normalized;
            }
            return series.isEmpty() ? (rows.isEmpty() ? "metric" : "table") : "chart";
        }

        private static String chooseChartType(Map<String, Object> spec,
                                       List<Map<String, Object>> rows,
                                       String xKey,
                                       List<Map<String, Object>> series) {
            String requested = firstTextValue(spec.get("chartType"), spec.get("chart"), "").toLowerCase();
            if (List.of("line", "bar", "pie", "scatter").contains(requested)) {
                return requested;
            }
            if (series.size() >= 2 && rows.size() > 2 && !isTimeKey(xKey, rows)) {
                return "scatter";
            }
            String label = (firstTextValue(spec.get("title"), "") + " " + firstTextValue(series.isEmpty() ? null : series.get(0).get("name"), "")).toLowerCase();
            if (rows.size() > 1 && rows.size() <= 8 && (label.contains("share") || label.contains("ratio") || label.contains("percent") || label.contains("鍗犳瘮") || label.contains("姣斾緥"))) {
                return "pie";
            }
            return isTimeKey(xKey, rows) ? "line" : "bar";
        }

        private static String firstNonNumericColumn(List<String> columns, List<Map<String, Object>> rows) {
            return columns.stream()
                .filter(column -> !hasNumeric(rows, column))
                .findFirst()
                .orElse(null);
        }

        private static boolean hasNumeric(List<Map<String, Object>> rows, String key) {
            return rows.stream().anyMatch(row -> number(row.get(key)) != null);
        }

        private static boolean isTimeKey(String key, List<Map<String, Object>> rows) {
            String normalized = key == null ? "" : key.toLowerCase();
            if (normalized.contains("date") || normalized.contains("time") || normalized.contains("month") || normalized.contains("year") || normalized.contains("day") || normalized.contains("week") || normalized.contains("quarter")) {
                return true;
            }
            return rows.stream().anyMatch(row -> {
                Object value = row.get(key);
                if (value == null) {
                    return false;
                }
                try {
                    Instant.parse(String.valueOf(value));
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            });
        }

        private static Double number(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value == null) {
                return null;
            }
            try {
                return Double.parseDouble(String.valueOf(value).replace(",", ""));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static String firstTextValue(Object... values) {
            for (Object value : values) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value).trim();
                }
            }
            return "";
        }

        private static Object firstPresent(Object first, Object second) {
            return first == null ? second : first;
        }
    }

    private record UiResponseContract(
        String contractVersion,
        String status,
        String answer,
        List<Map<String, Object>> citations,
        List<Map<String, Object>> evidencePremises,
        Double confidence,
        String evidenceSummary,
        Map<String, Object> visualization,
        Object visualizationSpec
    ) {

        private Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("contractVersion", firstText(contractVersion, "ui_response_v1"));
            values.put("status", firstText(status, "UNKNOWN"));
            values.put("answer", firstText(answer, ""));
            values.put("citations", citations == null ? List.of() : citations);
            values.put("evidencePremises", evidencePremises == null ? List.of() : evidencePremises);
            values.put("confidence", confidence);
            values.put("evidenceSummary", firstText(evidenceSummary, ""));
            values.put("visualization", visualization == null ? Map.of("type", "none") : visualization);
            if (visualizationSpec != null) {
                values.put("visualizationSpec", visualizationSpec);
            }
            return values;
        }

        private static String firstText(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private static class AgentTaskStoppedException extends CancellationException {

        private final String status;

        private AgentTaskStoppedException(String status, String message) {
            super(message);
            this.status = status;
        }
    }
}
