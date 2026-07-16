package com.chatchat.agents.runtime.toolcall;

import java.util.List;
import java.util.Map;

/** Model-facing semantic tool call. It contains no MCP transport details. */
public record ToolCallRequest(
    String requestId,
    String toolName,
    String action,
    Map<String, Object> parameters,
    ToolCallContext context
) {
    public record ToolCallContext(
        String purpose,
        String stepId,
        List<String> dependsOn,
        Map<String, Object> target
    ) {
    }
}
