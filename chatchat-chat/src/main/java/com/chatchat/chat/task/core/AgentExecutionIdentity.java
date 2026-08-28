package com.chatchat.chat.task.core;

import java.util.UUID;

/** Stable execution lineage shared by Task, Runtime, plan, evidence and delivery records. */
public record AgentExecutionIdentity(
    String executionId,
    String rootExecutionId,
    String attemptId,
    String parentAttemptId,
    int attemptNumber
) {

    public AgentExecutionIdentity {
        executionId = required(executionId, "executionId");
        rootExecutionId = required(rootExecutionId, "rootExecutionId");
        attemptId = required(attemptId, "attemptId");
        attemptNumber = Math.max(1, attemptNumber);
        parentAttemptId = normalized(parentAttemptId);
    }

    public static AgentExecutionIdentity initial(String taskId) {
        String executionId = required(taskId, "taskId");
        return new AgentExecutionIdentity(
            executionId,
            executionId,
            newAttemptId(executionId, 1),
            null,
            1
        );
    }

    public AgentExecutionIdentity retry() {
        int nextAttempt = attemptNumber + 1;
        return new AgentExecutionIdentity(
            required(executionId, "executionId"),
            required(rootExecutionId, "rootExecutionId"),
            newAttemptId(rootExecutionId, nextAttempt),
            attemptId,
            nextAttempt
        );
    }

    public static AgentExecutionIdentity from(AgentTaskLatestEntity task) {
        String taskId = required(task.getTaskId(), "taskId");
        String executionId = first(task.getExecutionId(), taskId);
        String rootId = first(task.getRootExecutionId(), executionId);
        int number = task.getExecutionAttemptNumber() == null
            ? 1 : Math.max(1, task.getExecutionAttemptNumber());
        String attemptId = first(task.getExecutionAttemptId(), newAttemptId(rootId, number));
        return new AgentExecutionIdentity(executionId, rootId, attemptId,
            task.getParentAttemptId(), number);
    }

    private static String newAttemptId(String rootExecutionId, int attemptNumber) {
        return "att-" + attemptNumber + "-" + UUID.randomUUID();
    }

    private static String first(String value, String fallback) {
        String normalized = normalized(value);
        return normalized == null ? fallback : normalized;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
