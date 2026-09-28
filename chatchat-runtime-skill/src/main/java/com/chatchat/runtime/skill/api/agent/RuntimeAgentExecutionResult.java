package com.chatchat.runtime.skill.api.agent;

import java.util.Map;

/** Engine-neutral result returned by an Agent runtime adapter. */
public record RuntimeAgentExecutionResult(String status, String output, Map<String, Object> metadata) {
    public RuntimeAgentExecutionResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
