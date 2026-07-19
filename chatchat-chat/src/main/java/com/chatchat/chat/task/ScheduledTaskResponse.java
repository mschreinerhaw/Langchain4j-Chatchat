package com.chatchat.chat.task;

import java.time.Instant;

public record ScheduledTaskResponse(
    String scheduleId,
    String taskId,
    String tenantId,
    String userId,
    String agentId,
    String name,
    Boolean enabled,
    String triggerType,
    String cronExpr,
    Long intervalSeconds,
    String payloadJson,
    String question,
    Boolean notifyEnabled,
    String notificationChannelId,
    String notificationChannelType,
    String notificationChannelName,
    Boolean tradingDayOnly,
    String status,
    Instant nextFireTime,
    Instant lastFireTime,
    Instant expiredAt,
    Integer maxRetries,
    Long retryDelaySeconds,
    Integer retryCount,
    String lastTaskId,
    String lastTaskStatus,
    String lastError,
    Instant createdAt,
    Instant updatedAt
) {

    static ScheduledTaskResponse from(ScheduledTaskEntity entity) {
        return new ScheduledTaskResponse(
            entity.getTaskId(),
            entity.getTaskId(),
            entity.getTenantId(),
            entity.getUserId(),
            entity.getAgentId(),
            entity.getName(),
            "ACTIVE".equals(entity.getStatus()) || "RUNNING".equals(entity.getStatus()),
            entity.getTriggerType(),
            entity.getCronExpr(),
            entity.getIntervalSeconds(),
            entity.getPayloadJson(),
            entity.getQuestion(),
            entity.getNotifyEnabled(),
            entity.getNotificationChannelId(),
            entity.getNotificationChannelType(),
            entity.getNotificationChannelName(),
            entity.getTradingDayOnly(),
            entity.getStatus(),
            entity.getNextFireTime(),
            entity.getLastFireTime(),
            entity.getExpiredAt(),
            entity.getMaxRetries(),
            entity.getRetryDelaySeconds(),
            entity.getRetryCount(),
            entity.getLastTaskId(),
            entity.getLastTaskStatus(),
            entity.getLastError(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
