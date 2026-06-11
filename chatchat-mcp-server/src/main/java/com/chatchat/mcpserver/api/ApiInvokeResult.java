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
    /**
     * Creates a new ApiInvokeResult instance.
     *
     * @param success the success value
     * @param statusCode the status code value
     * @param headers the headers value
     * @param body the body value
     * @param rawBody the raw body value
     * @param errorMessage the error message value
     */
    public ApiInvokeResult(boolean success, int statusCode, Map<String, List<String>> headers, Object body,
                           String rawBody, String errorMessage) {
        this(success, statusCode, headers, body, rawBody, errorMessage, false);
    }

    /**
     * Performs the with cache hit operation.
     *
     * @param cacheHit the cache hit value
     * @return the operation result
     */
    public ApiInvokeResult withCacheHit(boolean cacheHit) {
        return new ApiInvokeResult(success, statusCode, headers, body, rawBody, errorMessage, cacheHit);
    }
}
