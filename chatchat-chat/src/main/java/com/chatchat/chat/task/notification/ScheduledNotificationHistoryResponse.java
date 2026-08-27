package com.chatchat.chat.task.notification;

import com.chatchat.chat.task.schedule.ScheduledTaskRunEntity;

import java.time.Instant;

public record ScheduledNotificationHistoryResponse(
    String runId,
    String scheduledTaskId,
    String taskId,
    String channelType,
    String channelName,
    String receiver,
    String status,
    Instant sentAt,
    String errorMessage,
    String decisionJson
) {

    public static ScheduledNotificationHistoryResponse from(ScheduledTaskRunEntity entity) {
        return new ScheduledNotificationHistoryResponse(
            entity.getRunId(),
            entity.getScheduledTaskId(),
            entity.getTaskId(),
            entity.getNotificationChannelType(),
            entity.getNotificationChannelName(),
            entity.getNotificationReceiver(),
            entity.getNotificationStatus(),
            entity.getNotificationSentAt(),
            entity.getNotificationError(),
            entity.getNotificationDecisionJson()
        );
    }
}
