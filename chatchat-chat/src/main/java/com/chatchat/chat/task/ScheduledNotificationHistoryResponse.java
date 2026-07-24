package com.chatchat.chat.task;

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

    static ScheduledNotificationHistoryResponse from(ScheduledTaskRunEntity entity) {
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
