package com.chatchat.agents.runtime.trace;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCallTrace(
    Integer step,
    String toolName,
    String displayName,
    Boolean success,
    Map<String, Object> input,
    String outputPreview,
    String errorMessage,
    Long durationMs,
    Long startedAt,
    Long finishedAt,
    Map<String, Object> governance,
    Map<String, Object> runtimeMetadata
) {

    public ToolCallTrace {
        governance = governance == null ? Map.of() : new LinkedHashMap<>(governance);
        input = input == null ? Map.of() : new LinkedHashMap<>(input);
        runtimeMetadata = runtimeMetadata == null ? Map.of() : new LinkedHashMap<>(runtimeMetadata);
    }
}
