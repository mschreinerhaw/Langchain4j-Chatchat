package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.tool.ToolParameter;

import java.time.Duration;
import java.util.List;

public record McpToolDefinition(
    String name,
    String displayName,
    String description,
    String capabilityCode,
    String provider,
    List<ToolParameter> parameters,
    boolean enabledByDefault,
    boolean agentCallable,
    Duration timeout
) {
    public McpToolDefinition {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
}
