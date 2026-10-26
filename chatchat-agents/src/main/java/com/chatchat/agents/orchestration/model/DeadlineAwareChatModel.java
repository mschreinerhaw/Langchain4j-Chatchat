package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Applies the request deadline to every synchronous model invocation. */
@Slf4j
public final class DeadlineAwareChatModel implements ChatModel {

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        2,
        32,
        60L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256),
        runnable -> {
            Thread thread = new Thread(runnable, "agent-model-deadline");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy()
    );

    private final ChatModel delegate;
    private final LongSupplier remainingTimeMs;
    private final java.util.function.BooleanSupplier cancelled;
    private final long maxCallMillis;

    ChatModel toolDelegate() { return delegate; }
    LongSupplier toolRemainingTimeMs() { return remainingTimeMs; }
    java.util.function.BooleanSupplier toolCancellation() { return cancelled; }
    long toolMaxCallMillis() { return maxCallMillis; }

    public DeadlineAwareChatModel(ChatModel delegate, LongSupplier remainingTimeMs) {
        this(delegate, remainingTimeMs, () -> false, 0);
    }

    public DeadlineAwareChatModel(ChatModel delegate, LongSupplier remainingTimeMs,
            java.util.function.BooleanSupplier cancelled, long maxCallMillis) {
        if (maxCallMillis < 0) throw new IllegalArgumentException("Negative per-call model budget");
        this.delegate = delegate;
        this.remainingTimeMs = remainingTimeMs;
        this.cancelled = cancelled;
        this.maxCallMillis = maxCallMillis;
    }

    @Override public String chat(String message) { return invoke(() -> delegate.chat(message)); }
    @Override public ChatResponse doChat(ChatRequest request) { return invoke(() -> delegate.chat(request)); }

    private void requireActive() {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Runtime task cancelled during model invocation");
    }

    private <T> T invoke(java.util.concurrent.Callable<T> operation) {
        requireActive();
        MeteredChatModel.CallTiming timing = MeteredChatModel.currentTiming();
        long remaining = remainingTimeMs.getAsLong();
        if (remaining == 0) throw exhausted();
        // Custom unbounded embedders retain direct execution only when no call cap exists.
        if (remaining < 0 && maxCallMillis == 0) {
            if (timing != null) timing.started();
            try { return operation.call(); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        long allowance = remaining < 0 ? maxCallMillis : maxCallMillis > 0 ? Math.min(remaining, maxCallMillis) : remaining;
        long started = System.nanoTime();
        long allowanceNanos = TimeUnit.MILLISECONDS.toNanos(allowance);
        Future<T> future;
        try {
            future = EXECUTOR.submit(() -> {
                requireActive();
                if (System.nanoTime()-started >= allowanceNanos || remainingTimeMs.getAsLong()==0) throw exhausted();
                if (timing != null) timing.started();
                return MeteredChatModel.withTiming(timing, operation);
            });
        } catch (RejectedExecutionException e) {
            throw new AgentDeadlineExceededException("Agent model deadline executor is saturated");
        }
        try {
            while (true) {
                requireActive();
                long left = allowanceNanos - (System.nanoTime()-started);
                long taskLeft = remainingTimeMs.getAsLong();
                if (taskLeft >= 0) left = Math.min(left, TimeUnit.MILLISECONDS.toNanos(taskLeft));
                if (left <= 0) throw exhausted();
                try {
                    T value=future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                    requireActive();
                    if(remainingTimeMs.getAsLong()==0 || System.nanoTime()-started>=allowanceNanos)throw exhausted();
                    return value;
                }
                catch (TimeoutException waitAgain) { /* Recheck cancellation and the Java deadline. */ }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Agent model invocation interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause()==null?e:e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause.getMessage(), cause);
        } finally {
            if (!future.isDone()) future.cancel(true);
            EXECUTOR.purge();
        }
    }

    private static AgentDeadlineExceededException exhausted() {
        return new AgentDeadlineExceededException("Agent execution time budget exhausted during model invocation");
    }
}
