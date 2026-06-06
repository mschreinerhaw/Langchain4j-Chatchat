package com.chatchat.api.mcp.model;

import java.util.Map;

public record McpToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema
) {
}
