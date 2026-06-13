package com.chatchat.agents.runtime;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.Map;

@Builder
public record AgentObservation(
    String type,
    String source,
    String content,
    Map<String, Object> metadata
) {

    public AgentObservation {
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    public static AgentObservation text(String type, String source, String content) {
        return new AgentObservation(type, source, content, Map.of());
    }
}
