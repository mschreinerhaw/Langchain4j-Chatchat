package com.chatchat.agents.runtime.batch;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCallRequest(
    String callId,
    String toolName,
    Map<String, Object> arguments
) {
    public ToolCallRequest {
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
    }
}
