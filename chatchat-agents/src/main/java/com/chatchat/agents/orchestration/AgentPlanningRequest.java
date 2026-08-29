package com.chatchat.agents.orchestration;

import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Map;

/** Immutable input contract for one planner decision. */
record AgentPlanningRequest(
    ChatModel chatModel,
    String query,
    String systemPrompt,
    List<String> availableTools,
    List<String> observations,
    List<String> boundDocumentIds,
    List<String> boundDocumentTags,
    List<String> mandatoryTools,
    boolean requireToolBeforeFinal,
    boolean requireDocumentWebVerification,
    String documentSearchTool,
    String verificationWebSearchTool,
    Map<String, Object> runtimeAttributes
) {
    AgentPlanningRequest {
        availableTools = immutableList(availableTools);
        observations = immutableList(observations);
        boundDocumentIds = immutableList(boundDocumentIds);
        boundDocumentTags = immutableList(boundDocumentTags);
        mandatoryTools = immutableList(mandatoryTools);
        runtimeAttributes = runtimeAttributes == null ? Map.of() : Map.copyOf(runtimeAttributes);
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
