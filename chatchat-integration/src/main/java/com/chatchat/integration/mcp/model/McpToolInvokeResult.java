package com.chatchat.integration.mcp.model;

public record McpToolInvokeResult(
    boolean success,
    Object data,
    String message,
    String errorMessage
) {
}
