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

    public DeadlineAwareChatModel(ChatModel delegate, LongSupplier remainingTimeMs) {
        this.delegate = delegate;
        this.remainingTimeMs = remainingTimeMs;
    }

    @Override
    public String chat(String message) {
        long remaining = remainingTimeMs.getAsLong();
        if (remaining < 0L) {
            return delegate.chat(message);
        }
        if (remaining == 0L) {
            throw new AgentDeadlineExceededException("Agent execution time budget exhausted before model invocation");
        }
        Future<String> future;
        try {
            future = EXECUTOR.submit(() -> delegate.chat(message));
        } catch (RejectedExecutionException ex) {
            throw new AgentDeadlineExceededException(
                "Agent model deadline executor is saturated");
        }
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
                log.warn("Agent model invocation exceeded the request deadline; cancellation was requested "
                    + "but delegate transport termination depends on its own interrupt and socket-timeout support");
            }
            throw new AgentDeadlineExceededException(
                "Agent execution time budget exhausted during model invocation");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Agent model invocation interrupted");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        long remaining = remainingTimeMs.getAsLong();
        if (remaining < 0L) {
            return delegate.chat(request);
        }
        if (remaining == 0L) {
            throw new AgentDeadlineExceededException("Agent execution time budget exhausted before model invocation");
        }
        Future<ChatResponse> future;
        try {
            future = EXECUTOR.submit(() -> delegate.chat(request));
        } catch (RejectedExecutionException ex) {
            throw new AgentDeadlineExceededException("Agent model deadline executor is saturated");
        }
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
                log.warn("Agent model invocation exceeded the request deadline; cancellation was requested "
                    + "but delegate transport termination depends on its own interrupt and socket-timeout support");
            }
            throw new AgentDeadlineExceededException(
                "Agent execution time budget exhausted during model invocation");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Agent model invocation interrupted");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }
}
