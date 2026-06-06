package com.chatchat.api.mcp.model;

public record McpToolInvokeResult(
    boolean success,
    Object data,
    String message,
    String errorMessage
) {
}
