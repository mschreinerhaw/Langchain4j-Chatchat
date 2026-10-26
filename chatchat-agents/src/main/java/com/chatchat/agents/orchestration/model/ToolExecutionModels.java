package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import java.util.function.LongSupplier;

/** Reuses the selected provider while the tool owns its durable usage ledger and deadline. */
public final class ToolExecutionModels {
    private ToolExecutionModels() { }

    public static ChatModel independent(ChatModel model, LongSupplier remainingTimeMs) {
        return independent(model, remainingTimeMs, () -> false, 0);
    }

    public static ChatModel independent(ChatModel model, LongSupplier remainingTimeMs,
            java.util.function.BooleanSupplier cancelled, long maxCallMillis) {
        while (true) {
            if (model instanceof MeteredChatModel metered) model = metered.toolDelegate();
            else if (model instanceof DeadlineAwareChatModel timed) {
                LongSupplier ownRemaining=remainingTimeMs, inherited=timed.toolRemainingTimeMs();
                remainingTimeMs=()->{
                    long own=ownRemaining.getAsLong(), parent=inherited.getAsLong();
                    return own<0?parent:parent<0?own:Math.min(own,parent);
                };
                var ownCancellation=cancelled;
                cancelled=()->ownCancellation.getAsBoolean()||timed.toolCancellation().getAsBoolean();
                long inheritedCap=timed.toolMaxCallMillis();
                if(inheritedCap>0)maxCallMillis=maxCallMillis>0?Math.min(maxCallMillis,inheritedCap):inheritedCap;
                model = timed.toolDelegate();
            }
            else {
                if (model instanceof CapacityGovernedChatModel governed) model = governed.forTool(remainingTimeMs);
                return new DeadlineAwareChatModel(model, remainingTimeMs, cancelled, maxCallMillis);
            }
        }
    }
}
