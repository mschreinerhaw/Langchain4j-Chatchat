package com.chatchat.mcpserver.ops;

import java.util.Map;

public record HttpRequestToolResult(
    boolean success,
    String method,
    String url,
    int statusCode,
    Object body,
    String rawBody,
    Map<String, Object> headers,
    long durationMs,
    String errorMessage
) {
}
