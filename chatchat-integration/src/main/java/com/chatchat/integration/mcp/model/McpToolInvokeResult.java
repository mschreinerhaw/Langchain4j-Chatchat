package com.chatchat.integration.mcp.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record McpToolInvokeResult(
    boolean success,
    Object data,
    Object rawData,
    String message,
    String errorMessage,
    String errorCode,
    boolean retryable,
    String action,
    Map<String, Object> executionState
) {
    public McpToolInvokeResult {
        executionState = executionState == null ? Map.of() : new LinkedHashMap<>(executionState);
    }

    public McpToolInvokeResult(boolean success, Object data, String message, String errorMessage) {
        this(success, data, data, message, errorMessage, null, false, null, Map.of());
    }

    /** Backward-compatible constructor; pre-protocol callers expose data as the raw result. */
    public McpToolInvokeResult(boolean success, Object data, String message, String errorMessage,
                               String errorCode, boolean retryable, String action,
                               Map<String, Object> executionState) {
        this(success, data, data, message, errorMessage, errorCode, retryable, action, executionState);
    }

    public static McpToolInvokeResult failure(String errorMessage, String errorCode, boolean retryable, String action) {
        return failure(errorMessage, errorCode, retryable, action, Map.of());
    }

    public static McpToolInvokeResult failure(String errorMessage, String errorCode, boolean retryable, String action,
                                              Map<String, Object> executionState) {
        return new McpToolInvokeResult(false, null, null, null, errorMessage, errorCode, retryable, action, executionState);
    }

    public McpToolInvokeResult withExecutionState(Map<String, Object> executionState) {
        return new McpToolInvokeResult(success, data, rawData, message, errorMessage, errorCode, retryable, action, executionState);
    }
}
