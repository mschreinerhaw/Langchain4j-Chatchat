package com.chatchat.runtime.skill.api.agent;

import java.util.Map;

/** Health-check request for one explicitly selected runtime engine. */
public record AgentRuntimeHealthRequest(String engine, Map<String, Object> attributes) {
    public AgentRuntimeHealthRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
