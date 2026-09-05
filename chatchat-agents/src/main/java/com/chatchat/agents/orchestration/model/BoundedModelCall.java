package com.chatchat.agents.orchestration.model;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounds waiting and outstanding provider calls even when a provider ignores interruption. */
public final class BoundedModelCall {
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), task -> {
            var thread = new Thread(task, "bounded-analysis-model"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private BoundedModelCall() {}
    public static final class LimitExceeded extends IllegalStateException {
        LimitExceeded(String message) { super(message); }
    }
    public static String call(Supplier<String> call, long timeoutMs, Runnable guard) {
        guard.run();
        if (timeoutMs <= 0) throw new LimitExceeded("ANALYSIS_MODEL_TIME_BUDGET_EXHAUSTED");
        Future<String> pending = null;
        try {
            pending = POOL.submit(call::get);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                guard.run();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new LimitExceeded("ANALYSIS_MODEL_TIME_BUDGET_EXHAUSTED");
                try { return pending.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException tick) { /* Recheck cancellation and remaining budget. */ }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new CancellationException("Analysis model cancelled");
        } catch (RejectedExecutionException ex) {
            throw new LimitExceeded("ANALYSIS_MODEL_CAPACITY_EXHAUSTED");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Analysis model failed", ex.getCause());
        } finally {
            if (pending != null) {
                pending.cancel(true);
                if (pending instanceof Runnable task) POOL.remove(task);
            }
        }
    }
}
