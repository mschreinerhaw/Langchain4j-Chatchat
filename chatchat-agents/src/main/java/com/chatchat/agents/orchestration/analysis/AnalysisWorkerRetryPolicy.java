package com.chatchat.agents.orchestration.analysis;

import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntConsumer;

/** Spark-style, chunk-local retry policy used inside a dataset worker. */
public final class AnalysisWorkerRetryPolicy {

    public <T> T execute(
        int maximumRetries,
        BooleanSupplier cancellationCheck,
        IntFunction<T> operation,
        IntConsumer attemptObserver,
        BiConsumer<Integer, RuntimeException> failureObserver
    ) {
        if (operation == null) throw new IllegalArgumentException("operation is required");
        BooleanSupplier cancelled = cancellationCheck == null ? () -> false : cancellationCheck;
        IntConsumer observed = attemptObserver == null ? ignored -> { } : attemptObserver;
        BiConsumer<Integer, RuntimeException> failed = failureObserver == null
            ? (ignored, failure) -> { } : failureObserver;
        int maximumAttempts = Math.max(0, maximumRetries) + 1;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Analysis worker was cancelled");
            }
            observed.accept(attempt);
            try {
                return operation.apply(attempt);
            } catch (CancellationException cancelledFailure) {
                throw cancelledFailure;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                failed.accept(attempt, failure);
            }
        }
        throw lastFailure == null
            ? new IllegalStateException("Analysis worker exhausted retries")
            : lastFailure;
    }
}
