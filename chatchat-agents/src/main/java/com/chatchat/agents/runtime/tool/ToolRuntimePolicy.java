package com.chatchat.agents.runtime.tool;

import lombok.Builder;

import java.util.Map;

@Builder
public record ToolRuntimePolicy(
    Boolean allowed,
    String reason,
    ToolRuntimeAction executionAction,
    String runtimeLevel,
    Integer maxCallsPerMinute,
    Boolean requiresAuthentication,
    Integer circuitBreakerFailureThreshold,
    Integer circuitBreakerOpenSeconds,
    Map<String, Object> attributes
) {
}
