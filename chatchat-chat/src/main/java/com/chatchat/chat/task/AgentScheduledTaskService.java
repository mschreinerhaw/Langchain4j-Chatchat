package com.chatchat.chat.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.integration.mcp.service.McpTradingCalendarClient;
import com.chatchat.integration.mcp.service.McpNotificationClient;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.chat.skills.SkillCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentScheduledTaskService {

    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of(
        "SUCCESS", "FAILED", "CANCELLED", "REJECTED", "TIMEOUT_CANCELLED", "KILLED"
    );
    private static final Set<String> FAILED_TASK_STATUSES = Set.of(
        "FAILED", "CANCELLED", "REJECTED", "TIMEOUT_CANCELLED", "KILLED"
    );

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ScheduledTaskRunRepository scheduledTaskRunRepository;
    private final AgentTaskLatestRepository latestRepository;
    private final AgentTaskService taskService;
    private final ObjectMapper objectMapper;
    private final AgentTaskProperties properties;
    private final McpTradingCalendarClient tradingCalendarClient;
    private final McpNotificationClient notificationClient;
    private final TenantNotificationRecipientService recipientService;
    private final EnterpriseAdminService enterpriseAdminService;
    private final SkillCatalogService skillCatalogService;
    private final NotificationContentFormatter notificationContentFormatter;
    private final AgentScheduleWindowPolicy scheduleWindowPolicy;

    @Transactional
    public ScheduledTaskResponse create(ScheduledAgentTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Scheduled task request cannot be null");
        }
        Instant now = Instant.now();
        AgentTaskSubmitRequest payload = normalizePayload(request);
        String triggerType = normalizeTriggerType(request.getTriggerType());
        boolean enabled = request.getEnabled() == null || request.getEnabled();

        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setTaskId(UUID.randomUUID().toString());
        entity.setTenantId(payload.getTenantId());
        entity.setUserId(payload.getUserId());
        entity.setAgentId(firstText(request.getAgentId(), payload.getAgentId(), payload.getSkillId()));
        requirePublishedAgent(entity.getAgentId());
        entity.setName(firstText(request.getName(), defaultScheduleName(entity.getAgentId(), payload.getQuery())));
        entity.setTriggerType(triggerType);
        entity.setCronExpr(normalizeCronExpr(triggerType, firstText(request.getCronExpr(), request.getCron())));
        entity.setIntervalSeconds(normalizeInterval(triggerType, request.getIntervalSeconds()));
        entity.setPayloadJson(writeJson(payload));
        entity.setQuestion(payload.getQuery());
        entity.setNotifyEnabled(Boolean.TRUE.equals(request.getNotifyEnabled()));
        if (Boolean.TRUE.equals(entity.getNotifyEnabled())) {
            McpNotificationClient.NotificationChannelOption option =
                notificationClient.requireEnabled(request.getNotificationChannelId());
            if (!option.recipientAware()) {
                throw new IllegalArgumentException("所选MCP通知通道未在URL或请求模板中使用{{receiver}}，无法保证租户隔离");
            }
            entity.setNotificationChannelId(option.id());
            entity.setNotificationChannelType(option.channel());
            entity.setNotificationChannelName(firstText(option.title(), option.toolName(), option.channel()));
            recipientService.receiver(entity.getTenantId(), option.channel())
                .orElseThrow(() -> new IllegalArgumentException("当前租户尚未绑定" + option.channel() + "接收人"));
        }
        entity.setTradingDayOnly(Boolean.TRUE.equals(request.getTradingDayOnly()));
        configureScheduleWindow(entity, request, triggerType);
        entity.setStatus(enabled ? "ACTIVE" : "PAUSED");
        Instant initialFireTime = enabled ? resolveInitialFireTime(request, entity, now) : null;
        entity.setNextFireTime(enabled ? alignInitialFireTime(entity, initialFireTime) : null);
        entity.setExpiredAt(request.getExpiredAt());
        entity.setMaxRetries(normalizeRetryCount(request.getMaxRetries()));
        entity.setRetryDelaySeconds(normalizeRetryDelay(request.getRetryDelaySeconds()));
        validateNotExpired(entity, now);
        return ScheduledTaskResponse.from(scheduledTaskRepository.save(entity));
    }

    @Transactional
    public ScheduledTaskResponse update(String tenantId, String scheduledTaskId, ScheduledAgentTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Scheduled task request cannot be null");
        }
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        if ("RUNNING".equals(normalizeStatus(entity.getStatus()))
            || scheduledTaskRunRepository.existsByScheduledTaskIdAndStatus(entity.getTaskId(), "RUNNING")) {
            throw new IllegalArgumentException("调度任务正在执行，暂时不能编辑，请在本次执行结束后重试");
        }

        Instant now = Instant.now();
        AgentTaskSubmitRequest payload = normalizePayload(request);
        String triggerType = normalizeTriggerType(request.getTriggerType());
        boolean enabled = request.getEnabled() == null
            ? "ACTIVE".equals(normalizeStatus(entity.getStatus()))
            : request.getEnabled();

        entity.setUserId(payload.getUserId());
        entity.setAgentId(firstText(request.getAgentId(), payload.getAgentId(), payload.getSkillId()));
        requirePublishedAgent(entity.getAgentId());
        entity.setName(firstText(request.getName(), defaultScheduleName(entity.getAgentId(), payload.getQuery())));
        entity.setTriggerType(triggerType);
        entity.setCronExpr(normalizeCronExpr(triggerType, firstText(request.getCronExpr(), request.getCron())));
        entity.setIntervalSeconds(normalizeInterval(triggerType, request.getIntervalSeconds()));
        entity.setPayloadJson(writeJson(payload));
        entity.setQuestion(payload.getQuery());
        entity.setNotifyEnabled(Boolean.TRUE.equals(request.getNotifyEnabled()));
        if (Boolean.TRUE.equals(entity.getNotifyEnabled())) {
            McpNotificationClient.NotificationChannelOption option =
                notificationClient.requireEnabled(request.getNotificationChannelId());
            if (!option.recipientAware()) {
                throw new IllegalArgumentException("所选MCP通知通道未在URL或请求模板中使用{{receiver}}，无法保证租户隔离");
            }
            entity.setNotificationChannelId(option.id());
            entity.setNotificationChannelType(option.channel());
            entity.setNotificationChannelName(firstText(option.title(), option.toolName(), option.channel()));
            recipientService.receiver(entity.getTenantId(), option.channel())
                .orElseThrow(() -> new IllegalArgumentException("当前租户尚未绑定" + option.channel() + "接收人"));
        } else {
            entity.setNotificationChannelId(null);
            entity.setNotificationChannelType(null);
            entity.setNotificationChannelName(null);
        }
        entity.setTradingDayOnly(Boolean.TRUE.equals(request.getTradingDayOnly()));
        configureScheduleWindow(entity, request, triggerType);
        entity.setExpiredAt(request.getExpiredAt());
        entity.setMaxRetries(normalizeRetryCount(request.getMaxRetries()));
        entity.setRetryDelaySeconds(normalizeRetryDelay(request.getRetryDelaySeconds()));
        entity.setRetryCount(0);
        entity.setLastError(null);
        entity.setStatus(enabled ? "ACTIVE" : "PAUSED");
        Instant initialFireTime = enabled ? resolveInitialFireTime(request, entity, now) : null;
        entity.setNextFireTime(enabled ? alignInitialFireTime(entity, initialFireTime) : null);
        validateNotExpired(entity, now);
        return ScheduledTaskResponse.from(scheduledTaskRepository.save(entity));
    }

    public Optional<ScheduledTaskResponse> get(String tenantId, String scheduledTaskId) {
        return Optional.of(toResponse(getForTenant(tenantId, scheduledTaskId)));
    }

    public List<ScheduledTaskResponse> list(String tenantId, String agentId, int page, int pageSize) {
        String normalizedTenant = requireTenant(tenantId);
        int normalizedPage = Math.max(0, page - 1);
        int normalizedSize = Math.max(1, Math.min(pageSize, 100));
        if (agentId != null && !agentId.isBlank()) {
            return toResponses(scheduledTaskRepository.findByTenantIdAndAgentIdOrderByCreatedAtDesc(
                normalizedTenant, agentId.trim(), PageRequest.of(normalizedPage, normalizedSize)
            ));
        }
        return toResponses(scheduledTaskRepository.findByTenantIdOrderByCreatedAtDesc(
            normalizedTenant, PageRequest.of(normalizedPage, normalizedSize)
        ));
    }

    private ScheduledTaskResponse toResponse(ScheduledTaskEntity entity) {
        boolean running = "RUNNING".equals(normalizeStatus(entity.getStatus()))
            || scheduledTaskRunRepository.existsByScheduledTaskIdAndStatus(entity.getTaskId(), "RUNNING");
        return ScheduledTaskResponse.from(entity, running);
    }

    private List<ScheduledTaskResponse> toResponses(List<ScheduledTaskEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<String> scheduleIds = entities.stream().map(ScheduledTaskEntity::getTaskId).toList();
        Set<String> runningScheduleIds = Set.copyOf(
            scheduledTaskRunRepository.findScheduledTaskIdsByStatus(scheduleIds, "RUNNING")
        );
        return entities.stream()
            .map(entity -> ScheduledTaskResponse.from(entity,
                "RUNNING".equals(normalizeStatus(entity.getStatus())) || runningScheduleIds.contains(entity.getTaskId())))
            .toList();
    }

    public List<ScheduledTaskResponse> list(String tenantId, int page, int pageSize) {
        return list(tenantId, null, page, pageSize);
    }

    @Transactional
    public ScheduledTaskResponse pause(String tenantId, String scheduledTaskId) {
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        if (List.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED").contains(normalizeStatus(entity.getStatus()))) {
            return ScheduledTaskResponse.from(entity);
        }
        entity.setStatus("PAUSED");
        entity.setNextFireTime(null);
        return ScheduledTaskResponse.from(scheduledTaskRepository.save(entity));
    }

    @Transactional
    public ScheduledTaskResponse resume(String tenantId, String scheduledTaskId) {
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        if (!"PAUSED".equals(normalizeStatus(entity.getStatus()))) {
            return ScheduledTaskResponse.from(entity);
        }
        requirePublishedAgent(entity.getAgentId());
        Instant now = Instant.now();
        validateNotExpired(entity, now);
        entity.setStatus("ACTIVE");
        Instant nextFireTime = nextFireTime(entity, now);
        entity.setNextFireTime(alignInitialFireTime(entity, nextFireTime == null ? now : nextFireTime));
        return ScheduledTaskResponse.from(scheduledTaskRepository.save(entity));
    }

    @Transactional
    public ScheduledTaskResponse cancel(String tenantId, String scheduledTaskId) {
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        boolean running = "RUNNING".equals(normalizeStatus(entity.getStatus()));
        entity.setStatus("CANCELLED");
        entity.setNextFireTime(null);
        if (running && entity.getLastTaskId() != null) {
            try {
                taskService.cancel(entity.getTenantId(), entity.getLastTaskId());
            } catch (Exception ex) {
                log.warn("Failed to cancel running Agent task {} for scheduledTaskId={}: {}",
                    entity.getLastTaskId(), entity.getTaskId(), ex.getMessage());
            }
        }
        return ScheduledTaskResponse.from(scheduledTaskRepository.save(entity));
    }

    @Transactional
    public void delete(String tenantId, String scheduledTaskId) {
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        scheduledTaskRepository.delete(entity);
    }

    @Transactional
    public ScheduledTaskRunResponse rerun(String tenantId, String scheduledTaskId) {
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        validateNotExpired(entity, Instant.now());
        return ScheduledTaskRunResponse.from(submitRun(entity, Instant.now(), true));
    }

    public List<ScheduledTaskRunResponse> history(String tenantId, String scheduledTaskId, int page, int pageSize) {
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        int normalizedPage = Math.max(0, page - 1);
        int normalizedSize = Math.max(1, Math.min(pageSize, 100));
        return scheduledTaskRunRepository
            .findByScheduledTaskIdOrderByFireTimeDesc(entity.getTaskId(), PageRequest.of(normalizedPage, normalizedSize))
            .stream()
            .map(ScheduledTaskRunResponse::from)
            .toList();
    }

    public List<ScheduledTaskRunResponse> historyByAgent(String tenantId, String agentId, int page, int pageSize) {
        String normalizedTenant = requireTenant(tenantId);
        String normalizedAgent = requireText(agentId, "Agent ID cannot be empty");
        int normalizedPage = Math.max(0, page - 1);
        int normalizedSize = Math.max(1, Math.min(pageSize, 100));
        return scheduledTaskRunRepository
            .findByTenantIdAndAgentIdOrderByFireTimeDesc(normalizedTenant, normalizedAgent, PageRequest.of(normalizedPage, normalizedSize))
            .stream()
            .map(ScheduledTaskRunResponse::from)
            .toList();
    }

    public ScheduledNotificationHistoryPageResponse notificationHistory(String tenantId, String scheduledTaskId,
                                                                         String keyword, int page, int pageSize) {
        ScheduledTaskEntity entity = getForTenant(tenantId, scheduledTaskId);
        int normalizedPage = Math.max(0, page - 1);
        int normalizedSize = Math.max(1, Math.min(pageSize, 100));
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<ScheduledTaskRunEntity> result = scheduledTaskRunRepository.searchNotificationHistory(
            entity.getTenantId(), entity.getTaskId(), normalizedKeyword, PageRequest.of(normalizedPage, normalizedSize)
        );
        return new ScheduledNotificationHistoryPageResponse(
            result.getContent().stream().map(ScheduledNotificationHistoryResponse::from).toList(),
            result.getTotalElements(), normalizedPage + 1, normalizedSize, result.getTotalPages()
        );
    }

    @Transactional
    public int scanDueTasks() {
        Instant now = Instant.now();
        int changed = expireActiveTasks(now);
        changed += observeRunningRuns(now);
        changed += observeRunningTasks(now);
        changed += fireDueTasks(now);
        return changed;
    }

    private int expireActiveTasks(Instant now) {
        List<ScheduledTaskEntity> expired = scheduledTaskRepository
            .findByStatusAndExpiredAtBeforeOrderByExpiredAtAsc("ACTIVE", now, batchPage());
        for (ScheduledTaskEntity entity : expired) {
            entity.setStatus("EXPIRED");
            entity.setNextFireTime(null);
            scheduledTaskRepository.save(entity);
        }
        return expired.size();
    }

    private int observeRunningTasks(Instant now) {
        List<ScheduledTaskEntity> running = scheduledTaskRepository
            .findByStatusAndLastTaskIdIsNotNullOrderByUpdatedAtAsc("RUNNING", batchPage());
        int changed = 0;
        for (ScheduledTaskEntity entity : running) {
            Optional<AgentTaskLatestEntity> latest = latestRepository.findById(entity.getLastTaskId());
            if (latest.isEmpty()) {
                markObservedFailure(entity, "FAILED", "Agent task snapshot not found", now);
                changed++;
                continue;
            }
            String taskStatus = normalizeStatus(latest.get().getStatus());
            if (!TERMINAL_TASK_STATUSES.contains(taskStatus)) {
                continue;
            }
            if ("SUCCESS".equals(taskStatus)) {
                markObservedSuccess(entity, now);
            } else {
                markObservedFailure(entity, taskStatus, latest.get().getErrorMessage(), now);
            }
            changed++;
        }
        return changed;
    }

    private int observeRunningRuns(Instant now) {
        List<ScheduledTaskRunEntity> running = scheduledTaskRunRepository
            .findByStatusOrderByUpdatedAtAsc("RUNNING", batchPage());
        int changed = 0;
        for (ScheduledTaskRunEntity run : running) {
            if (run.getTaskId() == null || run.getTaskId().isBlank()) {
                markRunFailure(run, "Agent task ID is empty", now);
                changed++;
                continue;
            }
            Optional<AgentTaskLatestEntity> latest = latestRepository.findById(run.getTaskId());
            if (latest.isEmpty()) {
                if (!claimRunCompletion(run)) {
                    continue;
                }
                markRunFailure(run, "Agent task snapshot not found", now);
                notifyRunCompletion(run, null, "FAILED", "Agent task snapshot not found");
                changed++;
                continue;
            }
            String taskStatus = normalizeStatus(latest.get().getStatus());
            if (!TERMINAL_TASK_STATUSES.contains(taskStatus)) {
                continue;
            }
            if (!claimRunCompletion(run)) {
                continue;
            }
            completeRun(run, latest.get(), now);
            notifyRunCompletion(run, latest.get(), taskStatus, latest.get().getErrorMessage());
            changed++;
        }
        return changed;
    }

    /**
     * Reconciles a scheduled run as soon as its Agent task reaches a terminal state.
     * The periodic scheduler scan remains as a recovery mechanism for missed events.
     */
    @Transactional
    public void reconcileTerminalTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        reconcileTerminalTask(taskId, Instant.now());
    }

    private void reconcileTerminalTask(String taskId, Instant now) {
        Optional<AgentTaskLatestEntity> latest = latestRepository.findById(taskId);
        if (latest.isEmpty() || !TERMINAL_TASK_STATUSES.contains(normalizeStatus(latest.get().getStatus()))) {
            return;
        }
        Optional<ScheduledTaskRunEntity> scheduledRun = scheduledTaskRunRepository
            .findFirstByTaskIdOrderByFireTimeDesc(taskId);
        if (scheduledRun.isEmpty() || !"RUNNING".equals(normalizeStatus(scheduledRun.get().getStatus()))) {
            return;
        }
        ScheduledTaskRunEntity run = scheduledRun.get();
        if (!claimRunCompletion(run)) {
            return;
        }
        String taskStatus = normalizeStatus(latest.get().getStatus());
        completeRun(run, latest.get(), now);
        notifyRunCompletion(run, latest.get(), taskStatus, latest.get().getErrorMessage());

        if (!Boolean.TRUE.equals(run.getManualRun())) {
            scheduledTaskRepository.findFirstByLastTaskId(taskId).ifPresent(schedule -> {
                if ("SUCCESS".equals(taskStatus)) {
                    markObservedSuccess(schedule, now);
                } else {
                    markObservedFailure(schedule, taskStatus, latest.get().getErrorMessage(), now);
                }
            });
        }
    }

    private boolean claimRunCompletion(ScheduledTaskRunEntity run) {
        if (scheduledTaskRunRepository.claimCompletion(run.getRunId()) != 1) {
            log.debug("Skipped Agent run completion already claimed by another scheduler instance runId={}",
                run.getRunId());
            return false;
        }
        run.setStatus("COMPLETING");
        return true;
    }

    private int fireDueTasks(Instant now) {
        List<ScheduledTaskEntity> dueTasks = scheduledTaskRepository
            .findByStatusAndNextFireTimeLessThanEqualOrderByNextFireTimeAsc("ACTIVE", now, batchPage());
        int fired = 0;
        for (ScheduledTaskEntity entity : dueTasks) {
            if (isExpired(entity, now)) {
                entity.setStatus("EXPIRED");
                entity.setNextFireTime(null);
                scheduledTaskRepository.save(entity);
                continue;
            }
            if (!scheduleWindowPolicy.allows(entity, now)) {
                entity.setNextFireTime(nextFireTimeAfterWindowSkip(entity, now));
                scheduledTaskRepository.save(entity);
                fired++;
                log.debug("Skipped Agent schedule outside execution window scheduleId={} nextFireTime={}",
                    entity.getTaskId(), entity.getNextFireTime());
                continue;
            }
            if (scheduledTaskRepository.claimDueTask(entity.getTaskId(), now) != 1) {
                log.debug("Skipped Agent schedule already claimed by another scheduler instance scheduleId={}",
                    entity.getTaskId());
                continue;
            }
            entity.setStatus("RUNNING");
            entity.setNextFireTime(null);
            submitScheduledRun(entity, now);
            fired++;
        }
        return fired;
    }

    private void submitScheduledRun(ScheduledTaskEntity entity, Instant now) {
        submitRun(entity, now, false);
    }

    private ScheduledTaskRunEntity submitRun(ScheduledTaskEntity entity, Instant now, boolean manualRun) {
        ScheduledTaskRunEntity run = newRun(entity, now, manualRun);
        scheduledTaskRunRepository.save(run);
        if (!skillCatalogService.isPublished(entity.getAgentId())) {
            finishUnpublishedAgentRun(run, entity, now);
            return run;
        }
        if (!enterpriseAdminService.canAccessAgent(entity.getUserId(), entity.getAgentId())) {
            finishAuthorizationDeniedRun(run, entity, now, manualRun);
            return run;
        }
        if (Boolean.TRUE.equals(entity.getTradingDayOnly())) {
            TradingDayGuardResult guard = checkTradingDay(entity, now);
            if (!guard.success()) {
                finishTradingDayGuardRun(run, entity, now, manualRun, "TRADING_DAY_CHECK_FAILED", guard.message());
                return run;
            }
            if (!guard.tradingDay()) {
                finishTradingDayGuardRun(run, entity, now, manualRun, "SKIPPED_NON_TRADING_DAY", null);
                return run;
            }
        }
        try {
            AgentTaskSubmitRequest payload = objectMapper.readValue(entity.getPayloadJson(), AgentTaskSubmitRequest.class);
            AgentTaskResponse response = taskService.submit(payload);
            run.setStatus("RUNNING");
            run.setTaskId(response.taskId());
            scheduledTaskRunRepository.save(run);
            if (!manualRun) {
                entity.setStatus("RUNNING");
                entity.setNextFireTime(null);
            }
            entity.setLastFireTime(now);
            entity.setLastTaskId(response.taskId());
            entity.setLastTaskStatus(response.status());
            entity.setLastError(null);
            scheduledTaskRepository.save(entity);
        } catch (Exception ex) {
            markRunFailure(run, ex.getMessage(), now);
            notifyRunCompletion(run, null, "FAILED", ex.getMessage());
            if (!manualRun) {
                markObservedFailure(entity, "FAILED", ex.getMessage(), now);
            } else {
                entity.setLastTaskStatus("FAILED");
                entity.setLastError(truncate(firstText(ex.getMessage(), "Agent task submission failed"), 1000));
                scheduledTaskRepository.save(entity);
            }
        }
        return run;
    }

    private TradingDayGuardResult checkTradingDay(ScheduledTaskEntity entity, Instant now) {
        try {
            LocalDate date = now.atZone(scheduleWindowPolicy.zoneId(entity)).toLocalDate();
            McpTradingCalendarClient.TradingDayResult result = tradingCalendarClient.check(date);
            return new TradingDayGuardResult(true, result.tradingDay(), result.message());
        } catch (Exception ex) {
            log.warn("Scheduled Agent trading-day check failed: {}", ex.getMessage());
            return new TradingDayGuardResult(false, false,
                "交易日判断失败：" + firstText(ex.getMessage(), "MCP交易日接口返回为空"));
        }
    }

    private void finishTradingDayGuardRun(ScheduledTaskRunEntity run, ScheduledTaskEntity entity, Instant now,
                                          boolean manualRun, String runStatus, String errorMessage) {
        run.setStatus(runStatus);
        run.setErrorMessage(truncate(errorMessage, 1000));
        run.setFinishedAt(now);
        run.setDurationMs(Math.max(0L, now.toEpochMilli() - run.getFireTime().toEpochMilli()));
        scheduledTaskRunRepository.save(run);

        entity.setLastFireTime(now);
        entity.setLastTaskId(null);
        entity.setLastTaskStatus(runStatus);
        entity.setLastError(truncate(errorMessage, 1000));
        if (!manualRun) {
            if ("ONCE".equals(entity.getTriggerType())) {
                entity.setStatus("TRADING_DAY_CHECK_FAILED".equals(runStatus) ? "FAILED" : "COMPLETED");
                entity.setNextFireTime(null);
            } else if (isExpired(entity, now)) {
                entity.setStatus("EXPIRED");
                entity.setNextFireTime(null);
            } else {
                entity.setStatus("ACTIVE");
                entity.setNextFireTime(nextFireTime(entity, now));
            }
        }
        scheduledTaskRepository.save(entity);
    }

    private void finishAuthorizationDeniedRun(ScheduledTaskRunEntity run, ScheduledTaskEntity entity,
                                              Instant now, boolean manualRun) {
        String errorMessage = "当前调度所属用户已无权使用Agent: " + firstText(entity.getAgentId(), "unknown");
        run.setStatus("AGENT_AUTHORIZATION_DENIED");
        run.setErrorMessage(truncate(errorMessage, 1000));
        run.setFinishedAt(now);
        run.setDurationMs(Math.max(0L, now.toEpochMilli() - run.getFireTime().toEpochMilli()));
        scheduledTaskRunRepository.save(run);

        entity.setLastFireTime(now);
        entity.setLastTaskId(null);
        entity.setLastTaskStatus("AGENT_AUTHORIZATION_DENIED");
        entity.setLastError(truncate(errorMessage, 1000));
        if (!manualRun) {
            entity.setStatus("PAUSED");
            entity.setNextFireTime(null);
        }
        scheduledTaskRepository.save(entity);
        log.warn("Scheduled Agent task paused because authorization was revoked scheduleId={} tenantId={} userId={} agentId={}",
            entity.getTaskId(), entity.getTenantId(), entity.getUserId(), entity.getAgentId());
    }

    private void finishUnpublishedAgentRun(ScheduledTaskRunEntity run, ScheduledTaskEntity entity, Instant now) {
        String errorMessage = "Agent未发布，不能执行调度: " + firstText(entity.getAgentId(), "unknown");
        run.setStatus("AGENT_UNPUBLISHED");
        run.setErrorMessage(truncate(errorMessage, 1000));
        run.setFinishedAt(now);
        run.setDurationMs(Math.max(0L, now.toEpochMilli() - run.getFireTime().toEpochMilli()));
        scheduledTaskRunRepository.save(run);

        entity.setLastFireTime(now);
        entity.setLastTaskId(null);
        entity.setLastTaskStatus("AGENT_UNPUBLISHED");
        entity.setLastError(truncate(errorMessage, 1000));
        entity.setStatus("PAUSED");
        entity.setNextFireTime(null);
        scheduledTaskRepository.save(entity);
        log.warn("Scheduled Agent task paused because Agent is unpublished scheduleId={} tenantId={} agentId={}",
            entity.getTaskId(), entity.getTenantId(), entity.getAgentId());
    }

    private void markObservedSuccess(ScheduledTaskEntity entity, Instant now) {
        if (entity.getLastTaskId() != null && !entity.getLastTaskId().isBlank()) {
            latestRepository.findById(entity.getLastTaskId()).ifPresent(latest -> completeRunByTaskId(entity.getLastTaskId(), latest, now));
        }
        entity.setLastTaskStatus("SUCCESS");
        entity.setLastError(null);
        entity.setRetryCount(0);
        if ("ONCE".equals(entity.getTriggerType())) {
            entity.setStatus(isExpired(entity, now) ? "EXPIRED" : "COMPLETED");
            entity.setNextFireTime(null);
        } else if (isExpired(entity, now)) {
            entity.setStatus("EXPIRED");
            entity.setNextFireTime(null);
        } else {
            entity.setStatus("ACTIVE");
            entity.setNextFireTime(nextFireTime(entity, now));
        }
        scheduledTaskRepository.save(entity);
    }

    private void markObservedFailure(ScheduledTaskEntity entity, String taskStatus, String errorMessage, Instant now) {
        if (entity.getLastTaskId() != null && !entity.getLastTaskId().isBlank()) {
            latestRepository.findById(entity.getLastTaskId()).ifPresent(latest -> completeRunByTaskId(entity.getLastTaskId(), latest, now));
        }
        entity.setLastTaskStatus(normalizeStatus(taskStatus));
        entity.setLastError(truncate(firstText(errorMessage, "Agent task failed"), 1000));
        if (canRetry(entity)) {
            entity.setRetryCount(entity.getRetryCount() + 1);
            entity.setStatus("ACTIVE");
            entity.setNextFireTime(now.plusSeconds(Math.max(1L, entity.getRetryDelaySeconds())));
        } else if ("ONCE".equals(entity.getTriggerType())) {
            entity.setStatus(isExpired(entity, now) ? "EXPIRED" : "FAILED");
            entity.setNextFireTime(null);
        } else if (isExpired(entity, now)) {
            entity.setStatus("EXPIRED");
            entity.setNextFireTime(null);
        } else {
            entity.setRetryCount(0);
            entity.setStatus("ACTIVE");
            entity.setNextFireTime(nextFireTime(entity, now));
        }
        scheduledTaskRepository.save(entity);
    }

    private ScheduledTaskRunEntity newRun(ScheduledTaskEntity entity, Instant now, boolean manualRun) {
        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setScheduledTaskId(entity.getTaskId());
        run.setTenantId(entity.getTenantId());
        run.setUserId(entity.getUserId());
        run.setAgentId(entity.getAgentId());
        run.setStatus("SCHEDULED");
        run.setQuestion(entity.getQuestion());
        run.setFireTime(now);
        run.setManualRun(manualRun);
        return run;
    }

    private void completeRunByTaskId(String taskId, AgentTaskLatestEntity latest, Instant now) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        scheduledTaskRunRepository.findFirstByTaskIdOrderByFireTimeDesc(taskId)
            .ifPresent(run -> completeRun(run, latest, now));
    }

    private void completeRun(ScheduledTaskRunEntity run, AgentTaskLatestEntity latest, Instant now) {
        run.setStatus(toRunStatus(latest.getStatus()));
        run.setAnswerSummary(truncate(latest.getAnswerSummary(), 4000));
        run.setErrorMessage(truncate(latest.getErrorMessage(), 1000));
        run.setFinishedAt(now);
        run.setDurationMs(Math.max(0L, now.toEpochMilli() - run.getFireTime().toEpochMilli()));
        scheduledTaskRunRepository.save(run);
    }

    private void markRunFailure(ScheduledTaskRunEntity run, String errorMessage, Instant now) {
        run.setStatus("FAILED");
        run.setErrorMessage(truncate(firstText(errorMessage, "Agent task failed"), 1000));
        run.setFinishedAt(now);
        run.setDurationMs(Math.max(0L, now.toEpochMilli() - run.getFireTime().toEpochMilli()));
        scheduledTaskRunRepository.save(run);
    }

    private void notifyRunCompletion(ScheduledTaskRunEntity run, AgentTaskLatestEntity latest,
                                     String taskStatus, String errorMessage) {
        if (run == null || run.getScheduledTaskId() == null) {
            return;
        }
        scheduledTaskRepository.findById(run.getScheduledTaskId()).ifPresent(entity -> {
            if (!Boolean.TRUE.equals(entity.getNotifyEnabled())
                || entity.getNotificationChannelId() == null || entity.getNotificationChannelId().isBlank()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            String normalizedStatus = normalizeStatus(taskStatus);
            boolean success = "SUCCESS".equals(normalizedStatus);
            NotificationMessage notification = success
                ? notificationMessage(entity, run, latest)
                : new NotificationMessage(firstText(errorMessage, "Agent任务执行失败"), List.of());
            String fixedAnswer = notification.answer();
            if (supportsContentProtocol(entity.getNotificationChannelType())) {
                Map<String, Object> contentProtocol = notificationContentFormatter.format(fixedAnswer);
                payload.put("contentProtocol", contentProtocol);
                // Compatibility for old MCP servers; protocol-aware servers ignore these two fields.
                payload.put("title", contentProtocol.get("title"));
                payload.put("content", fixedAnswer);
            } else if (isSmsChannel(entity.getNotificationChannelType())) {
                payload.put("title", notificationContentFormatter.extractTitle(fixedAnswer, 36));
                payload.put("content", smsBrief(entity.getName(), normalizedStatus));
            } else {
                payload.put("title", "Agent调度完成：" + entity.getName());
                payload.put("content", fixedAnswer);
            }
            payload.put("level", success ? "INFO" : "CRITICAL");
            payload.put("sourceTaskId", firstText(run.getTaskId(), entity.getTaskId()));
            payload.put("scheduleId", entity.getTaskId());
            payload.put("scheduleName", entity.getName());
            payload.put("status", normalizedStatus);
            if (!notification.references().isEmpty()) {
                payload.put("references", notification.references());
            }
            String receiver = recipientService.receiver(entity.getTenantId(), entity.getNotificationChannelType())
                .orElse(null);
            if (receiver == null) {
                recordNotificationResult(run, entity, null, "SKIPPED", "当前租户未绑定通知接收人");
                log.warn("Skip scheduled Agent notification because tenant recipient is not bound scheduleId={} tenantId={} channel={}",
                    entity.getTaskId(), entity.getTenantId(), entity.getNotificationChannelType());
                return;
            }
            payload.put("receiver", receiver);
            Instant notificationStartedAt = Instant.now();
            if (scheduledTaskRunRepository.claimNotification(run.getRunId(), notificationStartedAt) != 1) {
                log.debug("Skipped Agent notification already claimed runId={} scheduleId={}",
                    run.getRunId(), entity.getTaskId());
                return;
            }
            run.setNotificationStatus("SENDING");
            run.setNotificationSentAt(notificationStartedAt);
            try {
                notificationClient.dispatch(entity.getNotificationChannelId(), payload);
                recordNotificationResult(run, entity, receiver, "SUCCESS", null);
            } catch (Exception ex) {
                recordNotificationResult(run, entity, receiver, "FAILED", ex.getMessage());
                log.warn("Failed to send scheduled Agent notification scheduleId={} channelId={}: {}",
                    entity.getTaskId(), entity.getNotificationChannelId(), ex.getMessage());
            }
        });
    }

    private NotificationMessage notificationMessage(ScheduledTaskEntity schedule, ScheduledTaskRunEntity run,
                                                    AgentTaskLatestEntity latest) {
        String taskId = firstText(run == null ? null : run.getTaskId(), latest == null ? null : latest.getTaskId());
        if (taskId != null) {
            try {
                Optional<AgentTaskService.AgentNotificationContent> content =
                    taskService.finalNotificationContent(schedule.getTenantId(), taskId);
                if (content.isPresent()) {
                    return new NotificationMessage(content.get().answer(), content.get().references());
                }
            } catch (Exception ex) {
                log.warn("Failed to load full Agent answer for scheduled notification scheduleId={} taskId={}: {}",
                    schedule.getTaskId(), taskId, ex.getMessage());
            }
        }
        return new NotificationMessage(
            firstText(latest == null ? null : latest.getAnswerSummary(), "Agent任务已成功完成"),
            List.of()
        );
    }

    private record NotificationMessage(String answer, List<Map<String, Object>> references) {
    }

    private boolean supportsContentProtocol(String channelType) {
        String channel = channelType == null ? "" : channelType.trim().toUpperCase(Locale.ROOT);
        return Set.of("EMAIL", "WECHAT_WORK", "DINGTALK").contains(channel);
    }

    private boolean isSmsChannel(String channelType) {
        return "SMS".equalsIgnoreCase(channelType == null ? "" : channelType.trim());
    }

    private String smsBrief(String scheduleName, String status) {
        String name = firstText(scheduleName, "Agent任务");
        name = name.substring(0, Math.min(32, name.length()));
        String statusText = "SUCCESS".equals(status) ? "成功" : "失败";
        return "任务：" + name + "；状态：" + statusText + "；详情请登录系统查看。";
    }

    private void recordNotificationResult(ScheduledTaskRunEntity run, ScheduledTaskEntity entity,
                                          String receiver, String status, String errorMessage) {
        run.setNotificationChannelType(entity.getNotificationChannelType());
        run.setNotificationChannelName(entity.getNotificationChannelName());
        run.setNotificationReceiver(truncate(receiver, 2000));
        run.setNotificationStatus(status);
        run.setNotificationSentAt(Instant.now());
        run.setNotificationError(truncate(errorMessage, 1000));
        scheduledTaskRunRepository.save(run);
    }

    private String toRunStatus(String taskStatus) {
        String normalized = normalizeStatus(taskStatus);
        return switch (normalized) {
            case "SUCCESS" -> "SUCCESS";
            case "CANCELLED", "REJECTED", "TIMEOUT_CANCELLED", "KILLED" -> "CANCELLED";
            default -> "FAILED";
        };
    }

    private boolean canRetry(ScheduledTaskEntity entity) {
        return entity.getRetryCount() != null
            && entity.getMaxRetries() != null
            && entity.getRetryCount() < entity.getMaxRetries()
            && FAILED_TASK_STATUSES.contains(normalizeStatus(entity.getLastTaskStatus()));
    }

    private AgentTaskSubmitRequest normalizePayload(ScheduledAgentTaskRequest request) {
        AgentTaskSubmitRequest payload = request.getPayload();
        if (payload == null && request.getPayloadJson() != null && !request.getPayloadJson().isBlank()) {
            try {
                payload = objectMapper.readValue(request.getPayloadJson(), AgentTaskSubmitRequest.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("Invalid payloadJson: " + ex.getOriginalMessage());
            }
        }
        if (payload == null) {
            payload = new AgentTaskSubmitRequest();
        }
        payload.setTenantId(requireTenant(firstText(payload.getTenantId(), request.getTenantId())));
        payload.setUserId(firstText(payload.getUserId(), request.getUserId(), "anonymous"));
        payload.setAgentId(firstText(payload.getAgentId(), request.getAgentId()));
        if ((payload.getSkillId() == null || payload.getSkillId().isBlank()) && payload.getAgentId() != null) {
            payload.setSkillId(payload.getAgentId());
        }
        payload.setQuery(firstText(payload.getQuery(), request.getQuestion()));
        if (payload.getQuery() == null || payload.getQuery().isBlank()) {
            throw new IllegalArgumentException("Scheduled task payload query cannot be empty");
        }
        return payload;
    }

    private Instant resolveInitialFireTime(ScheduledAgentTaskRequest request, ScheduledTaskEntity entity, Instant now) {
        if (request.getNextFireTime() != null) {
            return request.getNextFireTime();
        }
        if ("ONCE".equals(entity.getTriggerType()) && request.getDelaySeconds() != null) {
            return now.plusSeconds(Math.max(0L, request.getDelaySeconds()));
        }
        if ("ONCE".equals(entity.getTriggerType())) {
            return now;
        }
        return nextFireTime(entity, now);
    }

    private Instant nextFireTime(ScheduledTaskEntity entity, Instant after) {
        return switch (entity.getTriggerType()) {
            case "ONCE" -> entity.getNextFireTime();
            case "INTERVAL" -> after.plusSeconds(Math.max(1L, entity.getIntervalSeconds()));
            case "CRON" -> {
                ZonedDateTime next = CronExpression.parse(entity.getCronExpr())
                    .next(ZonedDateTime.ofInstant(after, scheduleWindowPolicy.zoneId(entity)));
                if (next == null) {
                    throw new IllegalArgumentException("Cron expression cannot produce a next fire time");
                }
                yield next.toInstant();
            }
            default -> throw new IllegalArgumentException("Unsupported trigger type: " + entity.getTriggerType());
        };
    }

    private String normalizeTriggerType(String value) {
        String normalized = value == null || value.isBlank() ? "ONCE" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONCE", "CRON", "INTERVAL" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported trigger type: " + value);
        };
    }

    private String normalizeCronExpr(String triggerType, String cronExpr) {
        if (!"CRON".equals(triggerType)) {
            return null;
        }
        if (cronExpr == null || cronExpr.isBlank()) {
            throw new IllegalArgumentException("cronExpr is required for CRON scheduled tasks");
        }
        String normalized = cronExpr.trim();
        CronExpression.parse(normalized);
        return normalized;
    }

    private Long normalizeInterval(String triggerType, Long intervalSeconds) {
        if (!"INTERVAL".equals(triggerType)) {
            return null;
        }
        if (intervalSeconds == null || intervalSeconds <= 0) {
            throw new IllegalArgumentException("intervalSeconds must be greater than 0 for INTERVAL scheduled tasks");
        }
        return intervalSeconds;
    }

    private void configureScheduleWindow(ScheduledTaskEntity entity, ScheduledAgentTaskRequest request,
                                         String triggerType) {
        boolean enabled = Boolean.TRUE.equals(request.getScheduleWindowEnabled());
        if (enabled && "ONCE".equals(triggerType)) {
            throw new IllegalArgumentException("一次性任务不能设置每日允许执行时段");
        }
        String zoneId = firstText(request.getZoneId(), AgentScheduleWindowPolicy.DEFAULT_ZONE_ID);
        ZoneId.of(zoneId);
        entity.setZoneId(zoneId);
        entity.setScheduleWindowEnabled(enabled);
        if (!enabled) {
            entity.setScheduleWindowStart(null);
            entity.setScheduleWindowEnd(null);
            return;
        }
        String start = normalizeWindowTime(request.getScheduleWindowStart(), "scheduleWindowStart");
        String end = normalizeWindowTime(request.getScheduleWindowEnd(), "scheduleWindowEnd");
        if (start.equals(end)) {
            throw new IllegalArgumentException("允许执行时段的开始时间和结束时间不能相同");
        }
        entity.setScheduleWindowStart(start);
        entity.setScheduleWindowEnd(end);
    }

    private String normalizeWindowTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required when schedule window is enabled");
        }
        return LocalTime.parse(value.trim()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    private Instant alignInitialFireTime(ScheduledTaskEntity entity, Instant candidate) {
        if (candidate == null || scheduleWindowPolicy.allows(entity, candidate)) return candidate;
        Instant windowStart = scheduleWindowPolicy.nextWindowStart(entity, candidate);
        if ("INTERVAL".equals(entity.getTriggerType())) return windowStart;
        if ("CRON".equals(entity.getTriggerType())) {
            Instant cronCandidate = nextFireTime(entity, windowStart.minusSeconds(1));
            return scheduleWindowPolicy.allows(entity, cronCandidate) ? cronCandidate : candidate;
        }
        return candidate;
    }

    private Instant nextFireTimeAfterWindowSkip(ScheduledTaskEntity entity, Instant now) {
        Instant normalNext = nextFireTime(entity, now);
        Instant windowStart = scheduleWindowPolicy.nextWindowStart(entity, now);
        if ("INTERVAL".equals(entity.getTriggerType())) {
            return normalNext.isAfter(windowStart) ? normalNext : windowStart;
        }
        if ("CRON".equals(entity.getTriggerType()) && normalNext.isBefore(windowStart)) {
            return nextFireTime(entity, windowStart.minusSeconds(1));
        }
        return normalNext;
    }

    private int normalizeRetryCount(Integer value) {
        return value == null ? Math.max(0, properties.getSchedulerDefaultMaxRetries()) : Math.max(0, value);
    }

    private long normalizeRetryDelay(Long value) {
        return value == null ? Math.max(1L, properties.getSchedulerDefaultRetryDelaySeconds()) : Math.max(1L, value);
    }

    private void validateNotExpired(ScheduledTaskEntity entity, Instant now) {
        if (isExpired(entity, now)) {
            throw new IllegalArgumentException("Scheduled task is already expired");
        }
    }

    private boolean isExpired(ScheduledTaskEntity entity, Instant now) {
        return entity.getExpiredAt() != null && !entity.getExpiredAt().isAfter(now);
    }

    private ScheduledTaskEntity getForTenant(String tenantId, String scheduledTaskId) {
        String normalizedTenant = requireTenant(tenantId);
        ScheduledTaskEntity entity = scheduledTaskRepository.findById(requireText(scheduledTaskId, "Scheduled task ID cannot be empty"))
            .orElseThrow(() -> new IllegalArgumentException("Scheduled task not found: " + scheduledTaskId));
        if (!normalizedTenant.equals(entity.getTenantId())) {
            throw new IllegalArgumentException("Scheduled task not found for tenant: " + scheduledTaskId);
        }
        return entity;
    }

    private PageRequest batchPage() {
        return PageRequest.of(0, Math.max(1, Math.min(properties.getSchedulerBatchSize(), 500)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize scheduled task payload", ex);
        }
    }

    private String requireTenant(String tenantId) {
        return requireText(tenantId, "Tenant ID cannot be empty");
    }

    private void requirePublishedAgent(String agentId) {
        if (!skillCatalogService.isPublished(agentId)) {
            throw new IllegalArgumentException("Agent未发布，不能创建或启用调度");
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizeStatus(String status) {
        return firstText(status, "UNKNOWN").toUpperCase(Locale.ROOT);
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

    private record TradingDayGuardResult(boolean success, boolean tradingDay, String message) {
    }

    private String defaultScheduleName(String agentId, String question) {
        String base = firstText(agentId, "Agent");
        String summary = truncate(firstText(question, "定时任务"), 24);
        return base + " - " + summary;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
