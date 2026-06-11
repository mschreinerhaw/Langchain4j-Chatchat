package com.chatchat.integration.mcp.model;

import java.util.Map;

public record McpToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    String category,
    String riskLevel,
    String operationType,
    Boolean userVisible,
    Map<String, Object> confirmation,
    Map<String, Object> permissions,
    Map<String, Object> inputPolicy,
    Map<String, Object> outputPolicy
) {
    public McpToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, null, null, null, null, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
