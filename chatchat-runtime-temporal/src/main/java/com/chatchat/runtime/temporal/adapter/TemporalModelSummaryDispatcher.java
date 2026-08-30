package com.chatchat.runtime.temporal.adapter;

import com.chatchat.agents.orchestration.analysis.dispatch.LocalAnalysisTaskDispatcher;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.model.ModelSummaryProgress;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressListener;
import com.chatchat.common.runtime.summary.spi.ModelSummaryWorker;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.contract.TemporalAnalysisBatchCommand;
import com.chatchat.runtime.temporal.contract.TemporalAnalysisBatchResult;
import com.chatchat.runtime.temporal.workflow.RuntimeOsAnalysisBatchWorkflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Temporal transport for durable dataset analysis, with a bounded payload safety fallback. */
public final class TemporalModelSummaryDispatcher implements ModelSummaryDispatcher<
    AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> {

    private final WorkflowClient client;
    private final TemporalWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final LocalAnalysisTaskDispatcher payloadFallback;
    private final Runnable workerStarter;

    public TemporalModelSummaryDispatcher(
        WorkflowClient client,
        TemporalWorkflowProperties properties,
        ObjectMapper objectMapper,
        Runnable workerStarter
    ) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.workerStarter = workerStarter == null ? () -> { } : workerStarter;
        this.payloadFallback = new LocalAnalysisTaskDispatcher(
            properties.analysisMaximumParallelism(), properties.activityHeartbeatSeconds() * 1_000L);
    }

    @Override
    public DispatchBatch<AnalysisTaskResult> dispatch(
        List<AnalysisTask> tasks,
        ModelSummaryWorker<AnalysisTask, AnalysisDatasetSummary> worker,
        BooleanSupplier cancellationCheck,
        ModelSummaryProgressListener progressListener
    ) {
        List<AnalysisTask> safeTasks = tasks == null ? List.of() : List.copyOf(tasks);
        if (safeTasks.isEmpty()) {
            return new EmptyBatch();
        }
        if (serializedBytes(safeTasks) > properties.analysisMaximumPayloadBytes()) {
            DispatchBatch<AnalysisTaskResult> local = payloadFallback.dispatch(
                safeTasks, worker, cancellationCheck, progressListener);
            return new ModeOverrideBatch(local, "LOCAL_PAYLOAD_FALLBACK");
        }
        workerStarter.run();
        String batchId = "analysis-" + ModelProtocolJson.sha256Hex(
            safeTasks.stream().map(AnalysisTask::idempotencyKey).toList()).substring(0, 32);
        TemporalAnalysisBatchCommand command = new TemporalAnalysisBatchCommand(
            batchId, safeTasks, properties.analysisMaximumParallelism(),
            properties.activityStartToCloseSeconds(), properties.activityHeartbeatSeconds(),
            properties.activityMaximumAttempts());
        RuntimeOsAnalysisBatchWorkflow workflow = client.newWorkflowStub(
            RuntimeOsAnalysisBatchWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(batchId)
                .setTaskQueue(properties.taskQueue())
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(workflow::execute, command);
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Stable batch IDs make re-entry attach to the authoritative durable execution.
        }
        CompletableFuture<TemporalAnalysisBatchResult> completion = client
            .newUntypedWorkflowStub(batchId)
            .getResultAsync(TemporalAnalysisBatchResult.class);
        return new TemporalDispatchBatch(batchId, workflow, completion, safeTasks.size(),
            Math.min(safeTasks.size(), properties.analysisMaximumParallelism()),
            cancellationCheck, progressListener);
    }

    private long serializedBytes(List<AnalysisTask> tasks) {
        try {
            return objectMapper.writeValueAsBytes(tasks).length;
        } catch (JsonProcessingException failed) {
            throw new IllegalArgumentException("Analysis tasks are not serializable", failed);
        }
    }

    private static final class TemporalDispatchBatch implements DispatchBatch<AnalysisTaskResult> {
        private final String batchId;
        private final RuntimeOsAnalysisBatchWorkflow workflow;
        private final CompletableFuture<TemporalAnalysisBatchResult> completion;
        private final int taskCount;
        private final int workerCount;
        private final BooleanSupplier cancellationCheck;
        private final ModelSummaryProgressListener progressListener;
        private int publishedProgress;
        private volatile boolean closed;

        private TemporalDispatchBatch(
            String batchId, RuntimeOsAnalysisBatchWorkflow workflow,
            CompletableFuture<TemporalAnalysisBatchResult> completion,
            int taskCount, int workerCount, BooleanSupplier cancellationCheck,
            ModelSummaryProgressListener progressListener
        ) {
            this.batchId = batchId;
            this.workflow = workflow;
            this.completion = completion;
            this.taskCount = taskCount;
            this.workerCount = workerCount;
            this.cancellationCheck = cancellationCheck == null ? () -> false : cancellationCheck;
            this.progressListener = progressListener == null
                ? ModelSummaryProgressListener.NOOP : progressListener;
        }

        @Override
        public AnalysisTaskResult await(String taskId) {
            while (true) {
                if (cancellationCheck.getAsBoolean()) {
                    cancel(taskId);
                    throw new CancellationException(
                        "Agent run was cancelled while awaiting dataset analysis " + taskId);
                }
                publishProgress();
                try {
                    TemporalAnalysisBatchResult result = completion.get(500L, TimeUnit.MILLISECONDS);
                    publish(result.progress());
                    return result.results().get(taskId);
                } catch (TimeoutException ignored) {
                    // Keep the caller responsive to its run-level cancellation contract.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cancel(taskId);
                    throw new CancellationException("Interrupted while awaiting dataset analysis " + taskId);
                } catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                    throw new CompletionException(cause);
                }
            }
        }

        private void publishProgress() {
            try {
                TemporalAnalysisBatchResult snapshot = workflow.status();
                if (snapshot != null) publish(snapshot.progress());
            } catch (RuntimeException ignored) {
                // Query visibility is observational; workflow completion remains authoritative.
            }
        }

        private synchronized void publish(List<ModelSummaryProgress> events) {
            List<ModelSummaryProgress> safe = events == null ? List.of() : events;
            while (publishedProgress < safe.size()) {
                progressListener.onProgress(safe.get(publishedProgress++));
            }
        }

        @Override public int taskCount() { return taskCount; }
        @Override public int workerCount() { return workerCount; }
        @Override public String mode() { return "TEMPORAL"; }
        @Override public boolean cancel(String taskId) {
            if (taskId == null || taskId.isBlank()) return false;
            workflow.cancelTask(taskId);
            return true;
        }
        @Override public boolean closed() { return closed; }
        @Override public void close() {
            closed = true;
            if (!completion.isDone()) {
                clientCancel(batchId);
            }
        }

        private void clientCancel(String workflowId) {
            WorkflowStub.fromTyped(workflow).cancel();
        }
    }

    private record ModeOverrideBatch(DispatchBatch<AnalysisTaskResult> delegate, String mode)
        implements DispatchBatch<AnalysisTaskResult> {
        @Override public AnalysisTaskResult await(String taskId) { return delegate.await(taskId); }
        @Override public int taskCount() { return delegate.taskCount(); }
        @Override public int workerCount() { return delegate.workerCount(); }
        @Override public boolean cancel(String taskId) { return delegate.cancel(taskId); }
        @Override public boolean closed() { return delegate.closed(); }
        @Override public void close() { delegate.close(); }
    }

    private static final class EmptyBatch implements DispatchBatch<AnalysisTaskResult> {
        private boolean closed;
        @Override public AnalysisTaskResult await(String taskId) { return null; }
        @Override public int taskCount() { return 0; }
        @Override public int workerCount() { return 0; }
        @Override public String mode() { return "TEMPORAL"; }
        @Override public boolean cancel(String taskId) { return false; }
        @Override public boolean closed() { return closed; }
        @Override public void close() { closed = true; }
    }
}
