package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import java.util.function.LongSupplier;

/** Reuses the selected provider while the tool owns its durable usage ledger and deadline. */
public final class ToolExecutionModels {
    private ToolExecutionModels() { }

    public static ChatModel independent(ChatModel model, LongSupplier remainingTimeMs) {
        while (true) {
            if (model instanceof MeteredChatModel metered) model = metered.toolDelegate();
            else if (model instanceof DeadlineAwareChatModel timed) model = timed.toolDelegate();
            else return new DeadlineAwareChatModel(model, remainingTimeMs);
        }
    }
}
