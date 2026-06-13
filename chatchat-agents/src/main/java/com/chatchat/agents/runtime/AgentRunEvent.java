package com.chatchat.agents.runtime;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Builder
public record AgentRunEvent(
    String eventId,
    String runId,
    AgentRunEventType type,
    long createdAt,
    String message,
    Map<String, Object> payload
) {

    public AgentRunEvent {
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        payload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
    }

    public static AgentRunEvent of(String runId, AgentRunEventType type, String message, Map<String, Object> payload) {
        return new AgentRunEvent(null, runId, type, System.currentTimeMillis(), message, payload);
    }
}
