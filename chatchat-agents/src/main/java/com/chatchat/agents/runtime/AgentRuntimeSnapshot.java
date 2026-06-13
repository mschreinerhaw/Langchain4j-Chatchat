package com.chatchat.agents.runtime;

import java.util.Collection;

public record AgentRuntimeSnapshot(
    long totalRuns,
    long pendingRuns,
    long runningRuns,
    long waitingConfirmationRuns,
    long completedRuns,
    long failedRuns,
    long cancelledRuns,
    long activeRuns,
    long terminalRuns,
    long activeCancellationSignals,
    long averageDurationMs,
    long lastUpdatedAt
) {

    public static AgentRuntimeSnapshot empty() {
        return new AgentRuntimeSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, System.currentTimeMillis());
    }

    public static AgentRuntimeSnapshot fromRuns(Collection<AgentRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return empty();
        }
        long pending = 0;
        long running = 0;
        long waitingConfirmation = 0;
        long completed = 0;
        long failed = 0;
        long cancelled = 0;
        long durationSum = 0;
        long durationCount = 0;
        long lastUpdatedAt = 0;
        for (AgentRun run : runs) {
            if (run == null) {
                continue;
            }
            AgentRunStatus status = run.status();
            if (status == AgentRunStatus.PENDING) {
                pending++;
            } else if (status == AgentRunStatus.RUNNING) {
                running++;
            } else if (status == AgentRunStatus.WAITING_CONFIRMATION) {
                waitingConfirmation++;
            } else if (status == AgentRunStatus.COMPLETED) {
                completed++;
            } else if (status == AgentRunStatus.FAILED) {
                failed++;
            } else if (status == AgentRunStatus.CANCELLED) {
                cancelled++;
            }
            long updatedAt = updatedAt(run);
            lastUpdatedAt = Math.max(lastUpdatedAt, updatedAt);
            if (run.finishedAt() != null && run.startedAt() > 0 && run.finishedAt() >= run.startedAt()) {
                durationSum += run.finishedAt() - run.startedAt();
                durationCount++;
            }
        }
        long terminal = completed + failed + cancelled;
        long active = pending + running + waitingConfirmation;
        long averageDurationMs = durationCount == 0 ? 0 : durationSum / durationCount;
        return new AgentRuntimeSnapshot(
            runs.size(),
            pending,
            running,
            waitingConfirmation,
            completed,
            failed,
            cancelled,
            active,
            terminal,
            0,
            averageDurationMs,
            lastUpdatedAt <= 0 ? System.currentTimeMillis() : lastUpdatedAt
        );
    }

    public AgentRuntimeSnapshot withActiveCancellationSignals(long value) {
        return new AgentRuntimeSnapshot(
            totalRuns,
            pendingRuns,
            runningRuns,
            waitingConfirmationRuns,
            completedRuns,
            failedRuns,
            cancelledRuns,
            activeRuns,
            terminalRuns,
            Math.max(0, value),
            averageDurationMs,
            lastUpdatedAt
        );
    }

    private static long updatedAt(AgentRun run) {
        if (run.finishedAt() != null) {
            return run.finishedAt();
        }
        if (!run.events().isEmpty()) {
            return run.events().get(run.events().size() - 1).createdAt();
        }
        return run.startedAt();
    }
}
