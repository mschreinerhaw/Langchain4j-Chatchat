package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDatasetExecutionPort;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.runtime.temporal.contract.TemporalAnalysisDatasetCommand;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Resolves the existing business analysis implementation inside the Activity process. */
public final class RuntimeOsAnalysisDatasetActivityImpl
    implements RuntimeOsAnalysisDatasetActivity {

    private final Supplier<AnalysisDatasetExecutionPort> executionPort;

    public RuntimeOsAnalysisDatasetActivityImpl(
        Supplier<AnalysisDatasetExecutionPort> executionPort
    ) {
        this.executionPort = executionPort;
    }

    @Override
    public AnalysisTaskResult execute(TemporalAnalysisDatasetCommand command) {
        AnalysisTask task = command.task();
        AnalysisDatasetExecutionPort port = executionPort == null ? null : executionPort.get();
        if (port == null) {
            throw new IllegalStateException("Analysis dataset execution port is unavailable");
        }
        ActivityExecutionContext context = Activity.getExecutionContext();
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "analysis-activity-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMs = Math.max(250L, command.heartbeatSeconds() * 500L);
        heartbeat.scheduleAtFixedRate(() -> context.heartbeat(Map.of(
            "stage", "DATA_PROCESSING_ACTIVE", "taskId", task.taskId())),
            intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        try {
            return port.execute(task, (stage, details) -> context.heartbeat(Map.of(
                "stage", stage == null ? "DATA_PROCESSING_UPDATED" : stage,
                "taskId", task.taskId(),
                "datasetReference", task.datasetReference(),
                "details", details == null ? Map.of() : details
            )));
        } finally {
            heartbeat.shutdownNow();
        }
    }
}
