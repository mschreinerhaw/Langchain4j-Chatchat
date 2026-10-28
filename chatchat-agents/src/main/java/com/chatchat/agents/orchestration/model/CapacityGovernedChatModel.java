package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/** Applies a shared per-model capacity gate without changing model-provider implementations. */
final class CapacityGovernedChatModel implements ChatModel {

    private final String modelKey;
    private final ChatModel delegate;
    private final ModelInvocationCapacityManager capacity;

    private final java.util.function.Function<java.time.Duration, ChatModel> boundedFactory;
    private final java.util.function.Supplier<ChatModel> currentDelegate;

    CapacityGovernedChatModel(String modelKey, ChatModel delegate, ModelInvocationCapacityManager capacity) {
        this(modelKey, delegate, capacity, null);
    }

    CapacityGovernedChatModel(String modelKey, ChatModel delegate,
                              ModelInvocationCapacityManager capacity,
                              java.util.function.Function<java.time.Duration, ChatModel> boundedFactory) {
        this(modelKey, delegate, capacity, boundedFactory, null);
    }

    CapacityGovernedChatModel(String modelKey, ChatModel delegate,
                              ModelInvocationCapacityManager capacity,
                              java.util.function.Function<java.time.Duration, ChatModel> boundedFactory,
                              java.util.function.Supplier<ChatModel> currentDelegate) {
        this.modelKey = modelKey;
        this.delegate = delegate;
        this.capacity = capacity;
        this.boundedFactory = boundedFactory;
        this.currentDelegate = currentDelegate;
    }

    ChatModel forTool(java.util.function.LongSupplier remaining) {
        if (boundedFactory == null) return this; // Custom embedded providers retain their adapter.
        return new ChatModel() {
            @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(ChatRequest request) {
                return capacity.invoke(modelKey, () -> {
                    long millis = remaining.getAsLong();
                    if (millis == 0) throw new AgentDeadlineExceededException("Task deadline reached before provider dispatch");
                    return boundedFactory.apply(java.time.Duration.ofMillis(millis < 0 ? Long.MAX_VALUE : millis)).chat(request);
                });
            }
        };
    }

    @Override
    public String chat(String message) {
        return capacity.invoke(modelKey, () -> current().chat(message));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return capacity.invoke(modelKey, () -> current().chat(request));
    }

    private ChatModel current() { return currentDelegate == null ? delegate : currentDelegate.get(); }
    ChatModel providerDelegate() { return delegate; }

    Class<?> delegateType() {
        return delegate.getClass();
    }

    /** The SDK's default text method delegates to the identical native ChatRequest. */
    boolean textUsesNativeRequest() {
        try {
            return delegate.getClass().getMethod("chat", String.class).getDeclaringClass() == ChatModel.class;
        } catch (NoSuchMethodException impossible) {
            return false;
        }
    }
}
