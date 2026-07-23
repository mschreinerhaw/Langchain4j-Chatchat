package com.chatchat.chat.task;

import java.time.Instant;

public record ScheduledTaskRunAuditResponse(
    String runId,
    String scheduledTaskId,
    String scheduleName,
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
    Boolean manualRun
) {
    static ScheduledTaskRunAuditResponse from(ScheduledTaskRunEntity entity, String scheduleName) {
        return new ScheduledTaskRunAuditResponse(
            entity.getRunId(), entity.getScheduledTaskId(), scheduleName, entity.getUserId(), entity.getAgentId(), entity.getTaskId(),
            entity.getStatus(), entity.getQuestion(), entity.getFireTime(), entity.getFinishedAt(), entity.getDurationMs(),
            entity.getAnswerSummary(), entity.getErrorMessage(), entity.getManualRun()
        );
    }
}
