package com.chatchat.agents.runtime;

import lombok.Builder;

import java.util.Map;

@Builder
public record ToolRuntimePolicy(
    Boolean allowed,
    String reason,
    ToolRuntimeAction executionAction,
    Integer maxCallsPerMinute,
    Boolean requiresAuthentication,
    Integer circuitBreakerFailureThreshold,
    Integer circuitBreakerOpenSeconds,
    Map<String, Object> attributes
) {
}
