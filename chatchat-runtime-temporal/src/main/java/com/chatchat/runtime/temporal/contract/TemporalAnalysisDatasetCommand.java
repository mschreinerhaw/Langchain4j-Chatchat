package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;

/** Activity input with an explicit heartbeat cadence for long model calls. */
public record TemporalAnalysisDatasetCommand(AnalysisTask task, long heartbeatSeconds) {
    public TemporalAnalysisDatasetCommand {
        if (task == null) throw new IllegalArgumentException("Analysis task is required");
        heartbeatSeconds = Math.max(1L, heartbeatSeconds);
    }
}
