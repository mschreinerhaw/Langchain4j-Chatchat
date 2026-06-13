package com.chatchat.chat.interaction.model;

import com.chatchat.chat.interaction.service.ConversationMemoryService;
import lombok.Builder;

import java.util.List;

/**
 * Runtime context shared across mode handlers.
 */
@Builder
public record InteractionContext(
    String requestId,
    String conversationId,
    InteractionMode mode,
    long startedAtMs,
    String conversationSummary,
    List<ConversationMemoryService.MessageSnapshot> history
) {
}

