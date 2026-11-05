package com.chatchat.agents.model;

import com.chatchat.agents.orchestration.model.MeteredChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the existing final-response contract; streams only inside the provider boundary. */
public final class RuntimeStreamingChatModel implements ChatModel {
    private final StreamingChatModel provider;
    private final java.time.Duration timeout;
    private final java.time.Duration firstTokenTimeout;
    RuntimeStreamingChatModel(StreamingChatModel provider) {
        this(provider, java.time.Duration.ofSeconds(com.chatchat.common.config.ModelsConfig.ModelConnectionConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS));
    }
    RuntimeStreamingChatModel(StreamingChatModel provider, java.time.Duration timeout) {
        this(provider,timeout,timeout);
    }
    RuntimeStreamingChatModel(StreamingChatModel provider, java.time.Duration timeout, java.time.Duration firstTokenTimeout) {
        if(timeout==null || timeout.isZero() || timeout.isNegative() || firstTokenTimeout==null
            || firstTokenTimeout.isZero() || firstTokenTimeout.isNegative()) throw new IllegalArgumentException("Positive model deadlines required");
        this.provider=provider; this.timeout=timeout; this.firstTokenTimeout=firstTokenTimeout;
    }

    @Override public ChatResponse doChat(ChatRequest request) {
        var result=new CompletableFuture<ChatResponse>();
        var firstActivity=new CompletableFuture<Long>();
        final long started=System.nanoTime();
        var subscription=new AtomicReference<Flow.Subscription>();
        var timing=MeteredChatModel.observeStream();
        try {
        provider.chat(request).subscribe(new Flow.Subscriber<ChatModelStreamingEvent>() {
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                if(result.isCancelled())value.cancel();else value.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ChatModelStreamingEvent event) {
                if(result.isDone())return;
                if(event instanceof PartialResponse text && !text.text().isEmpty()
                    || event instanceof PartialThinking thinking && !thinking.text().isEmpty()
                    || event instanceof PartialToolCall tool && (tool.name()!=null && !tool.name().isEmpty()
                        || tool.partialArguments()!=null && !tool.partialArguments().isEmpty()))
                    { timing.token();firstActivity.complete(System.nanoTime()); }
                if(event instanceof CompleteResponse complete) {
                    timing.complete();firstActivity.complete(System.nanoTime());result.complete(complete.chatResponse());
                }
            }
            @Override public void onError(Throwable error) { firstActivity.completeExceptionally(error);result.completeExceptionally(error); }
            @Override public void onComplete() {
                if(!result.isDone())onError(new IllegalStateException("Provider stream ended without a complete response"));
            }
        });
        try {
            long firstBudget=Math.min(firstTokenTimeout.toNanos(),timeout.toNanos());
            long arrived=firstActivity.get(Math.max(1,firstBudget-(System.nanoTime()-started)),TimeUnit.NANOSECONDS);
            if(arrived-started>firstBudget)throw new TimeoutException();
        } catch(TimeoutException expired) {
            if(firstTokenTimeout.compareTo(timeout)<0)throw new FirstTokenTimeoutException(expired);
            throw expired;
        }
        long remaining=timeout.toNanos()-(System.nanoTime()-started);
        if(remaining<=0)throw new TimeoutException();
        return result.get(Math.max(1,remaining), TimeUnit.NANOSECONDS);
        } catch(FirstTokenTimeoutException expired) {
            result.cancel(false);
            throw expired;
        } catch(TimeoutException expired) {
            result.cancel(false);
            throw new RequestTimeoutException(timeout.toMillis(),expired);
        } catch(InterruptedException interrupted) {
            result.cancel(false);Thread.currentThread().interrupt();
            throw new CancellationException("Runtime cancelled the provider stream");
        } catch(ExecutionException failure) {
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            throw new IllegalStateException("Provider stream failed",failure.getCause());
        } finally {
            var current=subscription.get();if(current!=null)current.cancel();
        }
    }
    public static final class FirstTokenTimeoutException extends IllegalStateException {
        FirstTokenTimeoutException(TimeoutException cause) { super("Model first token deadline exceeded",cause); }
    }
    public static final class RequestTimeoutException extends IllegalStateException {
        RequestTimeoutException(long millis,TimeoutException cause) { super("Model API request timed out after " + millis + "ms",cause); }
    }
}
