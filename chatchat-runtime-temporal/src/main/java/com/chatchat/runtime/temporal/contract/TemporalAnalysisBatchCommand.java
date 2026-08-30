package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;

import java.util.List;

/** Serializable command for one durable business-analysis batch. */
public record TemporalAnalysisBatchCommand(
    String batchId,
    List<AnalysisTask> tasks,
    int maximumParallelism,
    long startToCloseSeconds,
    long heartbeatSeconds,
    int maximumAttempts
) {
    public TemporalAnalysisBatchCommand {
        batchId = batchId == null || batchId.isBlank() ? "analysis-batch" : batchId.trim();
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        maximumParallelism = Math.max(1, maximumParallelism);
        startToCloseSeconds = Math.max(60L, startToCloseSeconds);
        heartbeatSeconds = Math.max(1L, Math.min(startToCloseSeconds, heartbeatSeconds));
        maximumAttempts = Math.max(1, maximumAttempts);
    }
}
