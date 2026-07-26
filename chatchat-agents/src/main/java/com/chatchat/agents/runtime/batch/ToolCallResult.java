package com.chatchat.agents.runtime.batch;

import java.util.Map;

public record ToolCallResult(
    String callId,
    String toolName,
    String templateCode,
    String status,
    long durationMs,
    String evidenceId,
    Object output,
    Map<String, Object> error
) {
}
