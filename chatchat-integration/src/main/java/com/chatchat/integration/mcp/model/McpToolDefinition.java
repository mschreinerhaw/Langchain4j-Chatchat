package com.chatchat.integration.mcp.model;

import java.util.Map;

public record McpToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema
) {
}
