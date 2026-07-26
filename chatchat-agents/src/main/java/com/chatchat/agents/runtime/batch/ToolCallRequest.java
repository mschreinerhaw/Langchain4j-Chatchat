package com.chatchat.agents.runtime.batch;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCallRequest(
    String callId,
    String toolName,
    Map<String, Object> arguments,
    Boolean emptyResultIsSuccess
) {
    public ToolCallRequest(String callId, String toolName, Map<String, Object> arguments) {
        this(callId, toolName, arguments, null);
    }

    public ToolCallRequest {
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
    }
}
