package com.chatchat.agents.runtime;

import java.util.List;

public interface AgentObservationPipeline {

    AgentObservation fromText(String content);

    default List<AgentObservation> fromTexts(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        return contents.stream()
            .filter(content -> content != null && !content.isBlank())
            .map(this::fromText)
            .toList();
    }
}
