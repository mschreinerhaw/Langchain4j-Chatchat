package com.chatchat.agents.orchestration;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** In-process bounded worker implementation of the analysis task dispatcher port. */
@Slf4j
public final class LocalAnalysisTaskDispatcher implements AnalysisTaskDispatcher {

    private final int maximumWorkers;

    public LocalAnalysisTaskDispatcher(int maximumWorkers) {
        this.maximumWorkers = Math.max(1, maximumWorkers);
    }

    @Override
    public DispatchBatch dispatch(
        List<AnalysisTask> tasks,
        AnalysisTaskWorker worker,
        BooleanSupplier cancellationCheck
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
            Future<AnalysisTaskResult> future = executor.submit(() -> execute(task, worker));
            submitted.put(task.taskId(), new SubmittedTask(task, future));
        }
        log.info("analysisTaskDispatcherStarted mode=LOCAL taskCount={} workerCount={}",
            submitted.size(), workerCount);
        return new LocalDispatchBatch(executor, submitted, cancellationCheck, workerCount);
    }

    private AnalysisTaskResult execute(AnalysisTask task, AnalysisTaskWorker worker) {
        String workerId = Thread.currentThread().getName();
        long startedAt = System.nanoTime();
        log.info("analysisTaskWorkerClaimed taskId={} idempotencyKey={} dataset={}/{} chunk={}/{} worker={}",
            task.taskId(), task.idempotencyKey(), task.datasetIndex(), task.datasetCount(),
            task.chunkIndex(), task.chunkCount(), workerId);
        try {
            AnalysisSummaryResult summary = worker.execute(task);
            task.isolationScope().requireSamePartition(summary.isolationScope());
            return AnalysisTaskResult.completed(task, workerId, summary,
                elapsedMillis(startedAt));
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failed) {
            return AnalysisTaskResult.failed(task, workerId, elapsedMillis(startedAt), failed);
        }
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record SubmittedTask(AnalysisTask task, Future<AnalysisTaskResult> future) { }

    private static final class LocalDispatchBatch implements DispatchBatch {
        private final ExecutorService executor;
        private final Map<String, SubmittedTask> tasks;
        private final BooleanSupplier cancellationCheck;
        private final int workerCount;

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
            long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(submitted.task().timeoutMs());
            while (true) {
                if (cancellationCheck.getAsBoolean()) {
                    submitted.future().cancel(true);
                    throw new CancellationException(
                        "Agent run was cancelled while awaiting analysis task " + taskId);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    submitted.future().cancel(true);
                    return AnalysisTaskResult.failed(submitted.task(), "driver-timeout",
                        submitted.task().timeoutMs(), new TimeoutException(
                            "Analysis task timed out after " + submitted.task().timeoutMs() + " ms"));
                }
                try {
                    return submitted.future().get(
                        Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                    // Poll cancellation while retaining an absolute task timeout.
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
        public void close() {
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
