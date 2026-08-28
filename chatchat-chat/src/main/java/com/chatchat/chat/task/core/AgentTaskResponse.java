package com.chatchat.chat.task.core;

import java.time.Instant;

public record AgentTaskResponse(
    String taskId,
    String executionId,
    String rootExecutionId,
    String attemptId,
    String parentAttemptId,
    int attemptNumber,
    String canonicalState,
    String tenantId,
    String userId,
    String agentId,
    String sessionId,
    String status,
    String question,
    String answerSummary,
    String errorMessage,
    Boolean feedbackUseful,
    Boolean feedbackAdopted,
    Boolean feedbackResolved,
    String feedbackComment,
    String feedbackReasonCategory,
    Instant feedbackTime,
    Instant createTime,
    Instant updateTime
) {

    /**
     * Creates the value from from.
     *
     * @param entity the entity value
     * @return the operation result
     */
    static AgentTaskResponse from(AgentTaskLatestEntity entity) {
        return new AgentTaskResponse(
            entity.getTaskId(),
            entity.getExecutionId(),
            entity.getRootExecutionId(),
            entity.getExecutionAttemptId(),
            entity.getParentAttemptId(),
            entity.getExecutionAttemptNumber() == null ? 1 : entity.getExecutionAttemptNumber(),
            entity.getCanonicalState(),
            entity.getTenantId(),
            entity.getUserId(),
            entity.getAgentId(),
            entity.getSessionId(),
            entity.getStatus(),
            entity.getQuestion(),
            entity.getAnswerSummary(),
            entity.getErrorMessage(),
            entity.getFeedbackUseful(),
            entity.getFeedbackAdopted(),
            entity.getFeedbackResolved(),
            entity.getFeedbackComment(),
            entity.getFeedbackReasonCategory(),
            entity.getFeedbackTime(),
            entity.getCreateTime(),
            entity.getUpdateTime()
        );
    }
}
