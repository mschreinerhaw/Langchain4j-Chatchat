package com.chatchat.chat.task;

import java.time.Instant;

public record ScheduledTaskRunResponse(
    String runId,
    String scheduledTaskId,
    String tenantId,
    String userId,
    String agentId,
    String taskId,
    String status,
    String question,
    Instant fireTime,
    Instant finishedAt,
    Long durationMs,
    String answerSummary,
    String errorMessage,
    Boolean manualRun,
    Instant createdAt,
    Instant updatedAt
) {

    static ScheduledTaskRunResponse from(ScheduledTaskRunEntity entity) {
        return new ScheduledTaskRunResponse(
            entity.getRunId(),
            entity.getScheduledTaskId(),
            entity.getTenantId(),
            entity.getUserId(),
            entity.getAgentId(),
            entity.getTaskId(),
            entity.getStatus(),
            entity.getQuestion(),
            entity.getFireTime(),
            entity.getFinishedAt(),
            entity.getDurationMs(),
            entity.getAnswerSummary(),
            entity.getErrorMessage(),
            entity.getManualRun(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
