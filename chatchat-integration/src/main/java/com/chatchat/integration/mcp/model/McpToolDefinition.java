package com.chatchat.integration.mcp.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record McpToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    String category,
    String riskLevel,
    String operationType,
    String runtimeLevel,
    Boolean userVisible,
    Map<String, Object> confirmation,
    Map<String, Object> permissions,
    Map<String, Object> inputPolicy,
    Map<String, Object> outputPolicy,
    Long timeoutMillis,
    Map<String, Object> meta
) {
    /**
     * Creates a new McpToolDefinition instance.
     *
     * @param name the name value
     * @param description the description value
     * @param inputSchema the input schema value
     */
    public McpToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, null, null, null, null, null, Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of());
    }

    public McpToolDefinition {
        meta = meta == null ? Map.of() : new LinkedHashMap<>(meta);
    }
}
