package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.event.RuntimeEvent;
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
) implements RuntimeEvent {

    public AgentRunEvent {
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        payload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
    }

    public static AgentRunEvent of(String runId, AgentRunEventType type, String message, Map<String, Object> payload) {
        return new AgentRunEvent(null, runId, type, System.currentTimeMillis(), message, payload);
    }

    @Override
    public String streamId() {
        return runId;
    }

    @Override
    public String eventType() {
        return type == null ? "UNKNOWN" : type.name();
    }

    @Override
    public long occurredAt() {
        return createdAt;
    }
}
