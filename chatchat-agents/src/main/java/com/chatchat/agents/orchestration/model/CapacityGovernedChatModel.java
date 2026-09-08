package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/** Applies a shared per-model capacity gate without changing model-provider implementations. */
final class CapacityGovernedChatModel implements ChatModel {

    private final String modelKey;
    private final ChatModel delegate;
    private final ModelInvocationCapacityManager capacity;

    CapacityGovernedChatModel(String modelKey, ChatModel delegate,
                              ModelInvocationCapacityManager capacity) {
        this.modelKey = modelKey;
        this.delegate = delegate;
        this.capacity = capacity;
    }

    @Override
    public String chat(String message) {
        return capacity.invoke(modelKey, () -> delegate.chat(message));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return capacity.invoke(modelKey, () -> delegate.chat(request));
    }

    Class<?> delegateType() {
        return delegate.getClass();
    }
}
