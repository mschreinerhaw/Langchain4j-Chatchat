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
    String explorationParentContext,
    List<ConversationMemoryService.MessageSnapshot> history,
    com.chatchat.common.runtime.capability.ProblemAnalysisPlan problemAnalysisPlan,
    java.util.Map<String, Object> skillAnalysisContext,
    com.chatchat.common.runtime.capability.ProblemAnalysisPlan.TaskType taskType
) {
    public static final String EXPLORATION_PARENT_CONTEXT = "__explorationParentContext";
}

