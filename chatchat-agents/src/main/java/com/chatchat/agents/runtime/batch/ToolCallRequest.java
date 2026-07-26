package com.chatchat.agents.runtime.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolCallRequest(
    String callId,
    String toolName,
    Map<String, Object> arguments,
    Boolean emptyResultIsSuccess,
    List<String> requiredFields
) {
    public ToolCallRequest(String callId, String toolName, Map<String, Object> arguments) {
        this(callId, toolName, arguments, null, List.of());
    }

    public ToolCallRequest(String callId,
                           String toolName,
                           Map<String, Object> arguments,
                           Boolean emptyResultIsSuccess) {
        this(callId, toolName, arguments, emptyResultIsSuccess, List.of());
    }

    public ToolCallRequest {
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        requiredFields = requiredFields == null ? List.of() : requiredFields.stream()
            .filter(field -> field != null && !field.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }
}
