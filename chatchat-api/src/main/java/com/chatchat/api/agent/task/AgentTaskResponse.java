package com.chatchat.api.agent.task;

import java.time.Instant;

public record AgentTaskResponse(
    String taskId,
    String tenantId,
    String userId,
    String agentId,
    String sessionId,
    String status,
    String question,
    String answerSummary,
    String errorMessage,
    Instant createTime,
    Instant updateTime
) {

    static AgentTaskResponse from(AgentTaskLatestEntity entity) {
        return new AgentTaskResponse(
            entity.getTaskId(),
            entity.getTenantId(),
            entity.getUserId(),
            entity.getAgentId(),
            entity.getSessionId(),
            entity.getStatus(),
            entity.getQuestion(),
            entity.getAnswerSummary(),
            entity.getErrorMessage(),
            entity.getCreateTime(),
            entity.getUpdateTime()
        );
    }
}
