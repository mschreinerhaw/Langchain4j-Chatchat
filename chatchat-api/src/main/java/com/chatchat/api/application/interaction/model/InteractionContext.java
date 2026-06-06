package com.chatchat.api.application.interaction.model;

import com.chatchat.api.application.interaction.service.ConversationMemoryService;
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
    List<ConversationMemoryService.MessageSnapshot> history
) {
}

