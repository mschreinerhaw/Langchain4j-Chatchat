package com.chatchat.mcpserver.ops;

import java.util.Map;
import java.util.LinkedHashMap;

public record HttpRequestToolResult(
    boolean success,
    String method,
    String url,
    int statusCode,
    Object body,
    String rawBody,
    Map<String, Object> headers,
    long durationMs,
    String errorMessage,
    Map<String, Object> sourceMetadata
) {
    public HttpRequestToolResult {
        headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        sourceMetadata = sourceMetadata == null ? Map.of() : new LinkedHashMap<>(sourceMetadata);
    }

    public HttpRequestToolResult(
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
        this(success, method, url, statusCode, body, rawBody, headers, durationMs, errorMessage, Map.of());
    }
}
