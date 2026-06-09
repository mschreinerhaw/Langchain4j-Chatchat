package com.chatchat.chat.task;

import com.chatchat.agents.runtime.ToolRuntimeSnapshot;

import java.util.List;

public record AgentRuntimeSummary(
    String runtimeName,
    String tenantId,
    long totalTasks,
    long activeTasks,
    long pendingTasks,
    long waitingTasks,
    long successTasks,
    long failedTasks,
    long cancelledTasks,
    long tenantCount,
    long activeWorkerCount,
    long queueDepth,
    ToolRuntimeSnapshot toolRuntime,
    List<StatusMetric> statuses,
    List<TenantMetric> tenants,
    List<AgentTaskResponse> latestTasks
) {

    public record StatusMetric(
        String status,
        long total
    ) {
    }

    public record TenantMetric(
        String tenantId,
        long total
    ) {
    }
}
