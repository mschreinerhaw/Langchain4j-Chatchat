package com.chatchat.common.knowledge.template;

import com.chatchat.common.runtime.event.RuntimeEvent;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.UUID;

/** Formal Runtime OS event emitted after context-aware business template matching. */
public record BusinessTemplateRequirementMatchingEvent(
    String eventId,
    String streamId,
    long occurredAt,
    Map<String, Object> templateMatchAnalysis
) implements RuntimeEvent {
    public BusinessTemplateRequirementMatchingEvent {
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        if (streamId == null || streamId.isBlank()) throw new IllegalArgumentException("streamId is required");
        occurredAt = occurredAt <= 0 ? System.currentTimeMillis() : occurredAt;
        if (templateMatchAnalysis == null || templateMatchAnalysis.isEmpty()) {
            throw new IllegalArgumentException("templateMatchAnalysis is required");
        }
        templateMatchAnalysis = Collections.unmodifiableMap(new LinkedHashMap<>(templateMatchAnalysis));
    }

    @Override public String eventType() {
        return TemplateMatchAnalysis.EVENT_TYPE;
    }

    @Override public Map<String, Object> payload() {
        return templateMatchAnalysis;
    }
}
