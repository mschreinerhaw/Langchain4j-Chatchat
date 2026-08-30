package com.chatchat.runtime.temporal.workflow;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.common.runtime.summary.model.ModelSummaryProgress;
import com.chatchat.runtime.temporal.activity.RuntimeOsAnalysisDatasetActivity;
import com.chatchat.runtime.temporal.contract.TemporalAnalysisBatchCommand;
import com.chatchat.runtime.temporal.contract.TemporalAnalysisDatasetCommand;
import com.chatchat.runtime.temporal.contract.TemporalAnalysisBatchResult;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable wave scheduler and completion barrier for business dataset analysis. */
public class RuntimeOsAnalysisBatchWorkflowImpl implements RuntimeOsAnalysisBatchWorkflow {

    private String batchId = "";
    private String status = "PENDING";
    private final Map<String, AnalysisTaskResult> results = new LinkedHashMap<>();
    private final List<ModelSummaryProgress> progress = new ArrayList<>();
    private final Set<String> cancelledTaskIds = new LinkedHashSet<>();

    @Override
    public TemporalAnalysisBatchResult execute(TemporalAnalysisBatchCommand command) {
        batchId = command.batchId();
        status = "RUNNING";
        RuntimeOsAnalysisDatasetActivity activity = Workflow.newActivityStub(
            RuntimeOsAnalysisDatasetActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(command.startToCloseSeconds()))
                .setHeartbeatTimeout(Duration.ofSeconds(command.heartbeatSeconds()))
                .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(command.maximumAttempts())
                    .build())
                .build());

        List<AnalysisTask> tasks = command.tasks();
        for (int offset = 0; offset < tasks.size(); offset += command.maximumParallelism()) {
            int end = Math.min(tasks.size(), offset + command.maximumParallelism());
            Map<AnalysisTask, Promise<AnalysisTaskResult>> wave = new LinkedHashMap<>();
            for (AnalysisTask task : tasks.subList(offset, end)) {
                if (cancelledTaskIds.contains(task.taskId())) {
                    results.put(task.taskId(), cancelled(task));
                    addProgress(task, "DATASET_CANCELLED", Map.of("reason", "cancelled before execution"));
                    continue;
                }
                addProgress(task, "WORKER_CLAIMED", Map.of("executionMode", "TEMPORAL_ACTIVITY"));
                wave.put(task, Async.function(activity::execute,
                    new TemporalAnalysisDatasetCommand(task, command.heartbeatSeconds())));
            }
            for (Map.Entry<AnalysisTask, Promise<AnalysisTaskResult>> entry : wave.entrySet()) {
                AnalysisTask task = entry.getKey();
                if (cancelledTaskIds.contains(task.taskId())) {
                    results.put(task.taskId(), cancelled(task));
                    addProgress(task, "DATASET_CANCELLED", Map.of("reason", "cancelled during execution"));
                    continue;
                }
                try {
                    AnalysisTaskResult result = entry.getValue().get();
                    results.put(task.taskId(), result);
                    addProgress(task, "FAILED".equalsIgnoreCase(result.status())
                        ? "DATASET_FAILED" : "DATASET_COMPLETED",
                        Map.of("durationMs", result.durationMs(), "status", result.status()));
                } catch (RuntimeException failed) {
                    AnalysisTaskResult result = AnalysisTaskResult.failed(
                        task, "temporal-analysis-activity", 0L, failed);
                    results.put(task.taskId(), result);
                    addProgress(task, "DATASET_FAILED", Map.of("error", message(failed)));
                }
            }
        }
        status = results.values().stream().anyMatch(result -> "FAILED".equalsIgnoreCase(result.status()))
            ? "COMPLETED_WITH_FAILURES" : "COMPLETED";
        return status();
    }

    @Override
    public void cancelTask(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            cancelledTaskIds.add(taskId.trim());
        }
    }

    @Override
    public TemporalAnalysisBatchResult status() {
        return new TemporalAnalysisBatchResult(batchId, status, results, progress);
    }

    private void addProgress(AnalysisTask task, String stage, Map<String, Object> details) {
        progress.add(new ModelSummaryProgress(ModelSummaryProgress.SCHEMA_VERSION, stage,
            task.taskId(), task.datasetReference(), task.datasetIndex(), task.datasetCount(),
            "analysis-service", Workflow.currentTimeMillis(), details));
    }

    private AnalysisTaskResult cancelled(AnalysisTask task) {
        return new AnalysisTaskResult(AnalysisTaskResult.SCHEMA_VERSION, task.taskId(),
            task.inputSha256(), "analysis-service", "CANCELLED", 0L, task.attempt(),
            null, "Dataset analysis was cancelled");
    }

    private String message(Throwable failure) {
        String value = failure == null ? null : failure.getMessage();
        return value == null || value.isBlank() ? "Dataset analysis failed" : value;
    }
}
