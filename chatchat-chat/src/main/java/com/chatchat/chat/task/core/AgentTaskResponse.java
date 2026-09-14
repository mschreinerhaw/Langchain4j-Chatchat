package com.chatchat.chat.task.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    Instant updateTime,
    Instant finishedAt,
    long lastEventSequence,
    List<Map<String, Object>> datasets
) {

    public AgentTaskResponse {
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
    }

    /**
     * Creates the value from from.
     *
     * @param entity the entity value
     * @return the operation result
     */
    static AgentTaskResponse from(AgentTaskLatestEntity entity) {
        return from(entity, 0L);
    }

    static AgentTaskResponse from(AgentTaskLatestEntity entity, long lastEventSequence) {
        AgentExecutionState state = AgentExecutionState.fromWire(entity.getCanonicalState());
        return from(entity, lastEventSequence, state.terminal() ? entity.getUpdateTime() : null);
    }

    static AgentTaskResponse from(AgentTaskLatestEntity entity,
                                  long lastEventSequence,
                                  Instant finishedAt) {
        AgentExecutionState state = AgentExecutionState.fromWire(entity.getCanonicalState());
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
            entity.getUpdateTime(),
            state.terminal() ? finishedAt : null,
            Math.max(0L, lastEventSequence),
            List.of()
        );
    }

    public AgentTaskResponse withDatasets(List<Map<String, Object>> values) {
        return new AgentTaskResponse(taskId, executionId, rootExecutionId, attemptId,
            parentAttemptId, attemptNumber, canonicalState, tenantId, userId, agentId,
            sessionId, status, question, answerSummary, errorMessage, feedbackUseful,
            feedbackAdopted, feedbackResolved, feedbackComment, feedbackReasonCategory,
            feedbackTime, createTime, updateTime, finishedAt, lastEventSequence, values);
    }
}
