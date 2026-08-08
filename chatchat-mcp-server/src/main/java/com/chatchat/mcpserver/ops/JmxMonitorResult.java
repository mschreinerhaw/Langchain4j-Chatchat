package com.chatchat.mcpserver.ops;

import java.util.List;
import java.util.Map;

public record JmxMonitorResult(
    boolean success,
    String template,
    String serviceUrl,
    List<Map<String, Object>> metrics,
    List<Map<String, Object>> errors,
    long durationMs,
    String errorMessage
) {
}
