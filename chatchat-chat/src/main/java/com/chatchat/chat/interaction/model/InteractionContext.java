package com.chatchat.chat.interaction.model;

import com.chatchat.chat.interaction.service.ConversationMemoryService;
import lombok.Builder;

import java.util.List;

/**
 * Runtime context shared across mode handlers.
 */
@Builder(toBuilder = true)
public record InteractionContext(
    String requestId,
    String conversationId,
    InteractionMode mode,
    long startedAtMs,
    String conversationSummary,
    String conversationEvidence,
    List<ConversationMemoryService.MessageSnapshot> history,
    com.chatchat.common.runtime.capability.ProblemAnalysisPlan problemAnalysisPlan,
    java.util.Map<String, Object> skillAnalysisContext
) {
}

