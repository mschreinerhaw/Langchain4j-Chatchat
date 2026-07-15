package com.chatchat.tools.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Transport-neutral result produced by a workflow node executor. */
public record SqlWorkflowNodeResult(
    boolean success,
    Map<String, Object> data,
    String errorMessage,
    long durationMs
) {
    public SqlWorkflowNodeResult {
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
