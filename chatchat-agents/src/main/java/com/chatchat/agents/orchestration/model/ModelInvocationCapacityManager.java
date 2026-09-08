package com.chatchat.agents.orchestration.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Runtime-wide, per-model concurrency and request-start rate gate. */
final class ModelInvocationCapacityManager {

    private final int maxConcurrent;
    private final long acquireTimeoutNanos;
    private final long startIntervalNanos;
    private final Map<String, ModelGate> gates = new ConcurrentHashMap<>();

    ModelInvocationCapacityManager(int maxConcurrent, int maxRequestsPerSecond, long acquireTimeoutMs) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, acquireTimeoutMs));
        this.startIntervalNanos = Math.max(1L,
            TimeUnit.SECONDS.toNanos(1) / Math.max(1, maxRequestsPerSecond));
    }

    <T> T invoke(String modelKey, Supplier<T> operation) {
        String key = modelKey == null || modelKey.isBlank() ? "default" : modelKey.trim();
        ModelGate gate = gates.computeIfAbsent(key, ignored -> new ModelGate(maxConcurrent));
        long waitingStarted = System.nanoTime();
        boolean acquired = false;
        try {
            acquired = gate.concurrent.tryAcquire(acquireTimeoutNanos, TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new ModelCapacityExceededException(
                    "MODEL_CONCURRENCY_CAPACITY_EXCEEDED: model=" + key);
            }
            long elapsed = Math.max(0L, System.nanoTime() - waitingStarted);
            long remaining = Math.max(0L, acquireTimeoutNanos - elapsed);
            long rateWait = gate.reserveStart(System.nanoTime(), remaining);
            if (rateWait < 0L) {
                throw new ModelCapacityExceededException(
                    "MODEL_RATE_CAPACITY_EXCEEDED: model=" + key);
            }
            if (rateWait > 0L) {
                TimeUnit.NANOSECONDS.sleep(rateWait);
            }
            MeteredChatModel.CallTiming timing = MeteredChatModel.currentTiming();
            if (timing != null) {
                timing.started();
            }
            return operation.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelCapacityExceededException(
                "MODEL_CAPACITY_WAIT_INTERRUPTED: model=" + key, interrupted);
        } finally {
            if (acquired) {
                gate.concurrent.release();
            }
        }
    }

    private final class ModelGate {
        private final Semaphore concurrent;
        private final AtomicLong nextStartNanos = new AtomicLong();

        private ModelGate(int permits) {
            this.concurrent = new Semaphore(permits, true);
        }

        private long reserveStart(long now, long maximumWait) {
            while (true) {
                long current = nextStartNanos.get();
                long reserved = Math.max(now, current);
                long wait = Math.max(0L, reserved - now);
                if (wait > maximumWait) {
                    return -1L;
                }
                if (nextStartNanos.compareAndSet(current, reserved + startIntervalNanos)) {
                    return wait;
                }
            }
        }
    }

    static final class ModelCapacityExceededException extends IllegalStateException {
        ModelCapacityExceededException(String message) {
            super(message);
        }

        ModelCapacityExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
