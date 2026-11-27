package com.chatchat.agents.orchestration.model;

import com.chatchat.common.kernel.KernelDataScope;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/** User-scoped model invocations outside a Coding task. */
public interface DirectModelInvocation {
    void freezeBillingRole(KernelDataScope scope);
    KernelDataScope authenticatedRequestScope();
    ChatResponse chat(KernelDataScope scope, String purpose, ChatModel model, ChatRequest request, Integer outputLimit);
    ChatResponse multimodal(KernelDataScope scope, String purpose, ChatModel model, ChatRequest request,
                            Integer contextWindow, Integer outputLimit);
    default String text(KernelDataScope scope, String purpose, String prompt, int outputLimit) {
        return text(scope, purpose, null, prompt, outputLimit);
    }
    String text(KernelDataScope scope, String purpose, String modelName, String prompt, int outputLimit);
}
