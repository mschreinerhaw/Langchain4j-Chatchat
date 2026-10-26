package com.chatchat.agents.model;

import com.chatchat.agents.orchestration.model.MeteredChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the existing final-response contract; streams only inside the provider boundary. */
final class RuntimeStreamingChatModel implements ChatModel {
    private final StreamingChatModel provider;
    RuntimeStreamingChatModel(StreamingChatModel provider) { this.provider=provider; }

    @Override public ChatResponse doChat(ChatRequest request) {
        var result=new CompletableFuture<ChatResponse>();
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
                    || event instanceof PartialToolCall tool && tool.partialArguments()!=null && !tool.partialArguments().isEmpty())
                    timing.token();
                if(event instanceof CompleteResponse complete) {
                    timing.complete();result.complete(complete.chatResponse());
                }
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() {
                if(!result.isDone())result.completeExceptionally(new IllegalStateException("Provider stream ended without a complete response"));
            }
        });
        return result.get();
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
}
