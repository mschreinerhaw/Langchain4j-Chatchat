package com.chatchat.mcpserver.api;

import java.util.List;
import java.util.Map;

public record ApiInvokeResult(
    boolean success,
    int statusCode,
    Map<String, List<String>> headers,
    Object body,
    String rawBody,
    String errorMessage,
    boolean cacheHit
) {
    public ApiInvokeResult(boolean success, int statusCode, Map<String, List<String>> headers, Object body,
                           String rawBody, String errorMessage) {
        this(success, statusCode, headers, body, rawBody, errorMessage, false);
    }

    public ApiInvokeResult withCacheHit(boolean cacheHit) {
        return new ApiInvokeResult(success, statusCode, headers, body, rawBody, errorMessage, cacheHit);
    }
}
