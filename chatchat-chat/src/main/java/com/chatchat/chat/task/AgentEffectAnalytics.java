package com.chatchat.chat.task;

import java.util.List;

public record AgentEffectAnalytics(
    String tenantId,
    long totalTasks,
    long successTasks,
    long failedTasks,
    long cancelledTasks,
    long feedbackTasks,
    long usefulTasks,
    long adoptedTasks,
    long resolvedTasks,
    double usefulRate,
    double adoptedRate,
    double resolvedRate,
    double failedRate,
    List<ReasonMetric> reasonMetrics,
    List<AgentMetric> agents,
    List<AgentTaskResponse> lowScoreTasks
) {

    public record AgentMetric(
        String agentId,
        long totalTasks,
        long successTasks,
        long failedTasks,
        long feedbackTasks,
        long usefulTasks,
        long adoptedTasks,
        long resolvedTasks,
        double usefulRate,
        double adoptedRate,
        double resolvedRate,
        double failedRate
    ) {
    }

    public record ReasonMetric(
        String reasonCategory,
        String label,
        long total,
        double share
    ) {
    }
}
