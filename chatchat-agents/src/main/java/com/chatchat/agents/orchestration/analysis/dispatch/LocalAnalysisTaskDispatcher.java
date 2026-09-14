package com.chatchat.agents.orchestration.analysis.dispatch;

import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;


import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.model.ModelSummaryProgress;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressListener;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressReporter;
import com.chatchat.common.runtime.summary.spi.ModelSummaryWorker;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** In-process bounded worker implementation of the analysis task dispatcher port. */
@Slf4j
public final class LocalAnalysisTaskDispatcher implements ModelSummaryDispatcher<
    AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> {

    private final int maximumWorkers;
    private final long heartbeatIntervalMs;

    public LocalAnalysisTaskDispatcher(int maximumWorkers) {
        this(maximumWorkers, 10_000L);
    }

    public LocalAnalysisTaskDispatcher(int maximumWorkers, long heartbeatIntervalMs) {
        this.maximumWorkers = Math.max(1, maximumWorkers);
        this.heartbeatIntervalMs = Math.max(250L, heartbeatIntervalMs);
    }

    @Override
    public ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> dispatch(
        List<AnalysisTask> tasks,
        ModelSummaryWorker<AnalysisTask, AnalysisDatasetSummary> worker,
        BooleanSupplier cancellationCheck,
        ModelSummaryProgressListener progressListener
    ) {
        List<AnalysisTask> safeTasks = tasks == null ? List.of() : List.copyOf(tasks);
        if (safeTasks.isEmpty()) {
            return LocalDispatchBatch.empty();
        }
        int workerCount = Math.min(maximumWorkers, safeTasks.size());
        AtomicInteger workerSequence = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable,
                "analysis-summary-worker-" + workerSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        Map<String, SubmittedTask> submitted = new LinkedHashMap<>();
        for (AnalysisTask task : safeTasks) {
            long submittedAt = System.nanoTime();
            long effectiveHeartbeatIntervalMs = Math.min(heartbeatIntervalMs,
                Math.max(10L, task.timeoutMs() / 2L));
            TaskLease lease = new TaskLease(Math.max(1_000L,
                Math.max(task.timeoutMs(), effectiveHeartbeatIntervalMs * 3L)));
            Future<AnalysisTaskResult> future = executor.submit(() ->
                execute(task, worker, progressListener, submittedAt, workerCount, lease));
            submitted.put(task.taskId(), new SubmittedTask(task, future, lease));
        }
        log.info("analysisTaskDispatcherStarted mode=LOCAL taskCount={} workerCount={}",
            submitted.size(), workerCount);
        return new LocalDispatchBatch(executor, submitted, cancellationCheck, workerCount);
    }

    private AnalysisTaskResult execute(
        AnalysisTask task,
        ModelSummaryWorker<AnalysisTask, AnalysisDatasetSummary> worker,
        ModelSummaryProgressListener progressListener,
        long submittedAt,
        int workerCount,
        TaskLease lease
    ) {
        String workerId = Thread.currentThread().getName();
        long startedAt = System.nanoTime();
        ModelSummaryProgressListener listener = progressListener == null
            ? ModelSummaryProgressListener.NOOP : progressListener;
        lease.claim();
        ModelSummaryProgressReporter reporter = (stage, details) -> {
            lease.heartbeat();
            try {
                listener.onProgress(progress(task, workerId, stage, details));
            } catch (RuntimeException telemetryFailure) {
                // Progress transport is observational. A broken UI/event sink must never turn a
                // healthy model computation into a Worker failure.
                log.warn("analysisTaskProgressPublishFailed taskId={} worker={} stage={} error={}",
                    task.taskId(), workerId, stage, telemetryFailure.getMessage());
            }
        };
        log.info("analysisTaskWorkerClaimed taskId={} idempotencyKey={} dataset={}/{} worker={}",
            task.taskId(), task.idempotencyKey(), task.datasetIndex(), task.datasetCount(),
            workerId);
        long queueTimeMs = Math.max(0, (startedAt - submittedAt) / 1_000_000);
        reporter.report("WORKER_CLAIMED", Map.of(
            "queueTimeMs", queueTimeMs, "configuredParallelism", workerCount));
        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, workerId + "-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
        long effectiveHeartbeatIntervalMs = Math.min(heartbeatIntervalMs,
            Math.max(10L, task.timeoutMs() / 2L));
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            () -> reporter.report("WORKER_HEARTBEAT", Map.of(
                "elapsedMs", elapsedMillis(startedAt),
                "heartbeatIntervalMs", effectiveHeartbeatIntervalMs,
                "heartbeatAt", System.currentTimeMillis())),
            effectiveHeartbeatIntervalMs, effectiveHeartbeatIntervalMs, TimeUnit.MILLISECONDS);
        try {
            AnalysisDatasetSummary summary = worker.execute(task, reporter);
            task.isolationScope().requireSamePartition(summary.isolationScope());
            long durationMs = elapsedMillis(startedAt);
            reporter.report("DATASET_COMPLETED", Map.of(
                "durationMs", durationMs,
                "chunkCount", summary.chunks().size(),
                "outcome", summary.outcome()));
            return AnalysisTaskResult.completed(task, workerId, summary, durationMs);
        } catch (CancellationException cancelled) {
            reporter.report("DATASET_CANCELLED", Map.of(
                "durationMs", elapsedMillis(startedAt),
                "reason", String.valueOf(cancelled.getMessage())));
            throw cancelled;
        } catch (RuntimeException failed) {
            long durationMs = elapsedMillis(startedAt);
            reporter.report("DATASET_FAILED", Map.of(
                "durationMs", durationMs,
                "error", String.valueOf(failed.getMessage())));
            return AnalysisTaskResult.failed(task, workerId, durationMs, failed);
        } finally {
            reporter.report("WORKER_EXECUTION_METRIC", Map.of(
                "queueTimeMs", queueTimeMs,
                "executionTimeMs", elapsedMillis(startedAt),
                "configuredParallelism", workerCount));
            log.info("analysisTaskExecutionMetric taskId={} queueTimeMs={} executionTimeMs={} configuredParallelism={}",
                task.taskId(), queueTimeMs, elapsedMillis(startedAt), workerCount);
            heartbeat.cancel(false);
            heartbeatExecutor.shutdownNow();
        }
    }

    private ModelSummaryProgress progress(
        AnalysisTask task,
        String workerId,
        String stage,
        Map<String, Object> details
    ) {
        return new ModelSummaryProgress(ModelSummaryProgress.SCHEMA_VERSION, stage,
            task.taskId(), task.datasetReference(), task.datasetIndex(), task.datasetCount(),
            workerId, System.currentTimeMillis(), details);
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record SubmittedTask(
        AnalysisTask task,
        Future<AnalysisTaskResult> future,
        TaskLease lease
    ) { }

    private static final class TaskLease {
        private final long timeoutMs;
        private final AtomicLong lastHeartbeatNanos = new AtomicLong();

        private TaskLease(long timeoutMs) {
            this.timeoutMs = Math.max(1L, timeoutMs);
        }

        private void claim() {
            lastHeartbeatNanos.set(System.nanoTime());
        }

        private void heartbeat() {
            lastHeartbeatNanos.set(System.nanoTime());
        }

        private boolean expired() {
            long heartbeat = lastHeartbeatNanos.get();
            return heartbeat > 0L
                && System.nanoTime() - heartbeat > TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }
    }

    private static final class LocalDispatchBatch
        implements ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> {
        private final ExecutorService executor;
        private final Map<String, SubmittedTask> tasks;
        private final BooleanSupplier cancellationCheck;
        private final int workerCount;
        private volatile boolean closed;

        private LocalDispatchBatch(
            ExecutorService executor,
            Map<String, SubmittedTask> tasks,
            BooleanSupplier cancellationCheck,
            int workerCount
        ) {
            this.executor = executor;
            this.tasks = Map.copyOf(tasks);
            this.cancellationCheck = cancellationCheck == null ? () -> false : cancellationCheck;
            this.workerCount = workerCount;
        }

        private static LocalDispatchBatch empty() {
            return new LocalDispatchBatch(null, Map.of(), () -> false, 0);
        }

        @Override
        public AnalysisTaskResult await(String taskId) {
            SubmittedTask submitted = tasks.get(taskId);
            if (submitted == null) {
                return null;
            }
            while (true) {
                if (cancellationCheck.getAsBoolean()) {
                    submitted.future().cancel(true);
                    throw new CancellationException(
                        "Agent run was cancelled while awaiting analysis task " + taskId);
                }
                long leaseTimeoutMs = submitted.lease().timeoutMs;
                if (submitted.lease().expired()) {
                    submitted.future().cancel(true);
                    TimeoutException expired = new TimeoutException(
                        "Worker heartbeat lease expired after " + leaseTimeoutMs + " ms");
                    return AnalysisTaskResult.failed(submitted.task(), "local-dispatcher",
                        leaseTimeoutMs, expired);
                }
                try {
                    return submitted.future().get(
                        Math.min(1_000L, Math.max(25L, leaseTimeoutMs / 4L)),
                        TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // A live Worker may legitimately spend longer than one model request on
                    // chunking and reduction. Continue only while its heartbeat lease is valid.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    submitted.future().cancel(true);
                    throw new CancellationException(
                        "Interrupted while awaiting analysis task " + taskId);
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof CancellationException cancelled) {
                        throw cancelled;
                    }
                    return AnalysisTaskResult.failed(submitted.task(), "local-dispatcher", 0, cause);
                }
            }
        }

        @Override
        public int taskCount() {
            return tasks.size();
        }

        @Override
        public int workerCount() {
            return workerCount;
        }

        @Override
        public String mode() {
            return "LOCAL";
        }

        @Override
        public boolean cancel(String taskId) {
            SubmittedTask submitted = tasks.get(taskId);
            return submitted != null && submitted.future().cancel(true);
        }

        @Override
        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
            if (executor == null) {
                return;
            }
            tasks.values().forEach(task -> {
                if (!task.future().isDone()) {
                    task.future().cancel(true);
                }
            });
            executor.shutdownNow();
        }
    }
}
