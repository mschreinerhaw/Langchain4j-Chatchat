package com.chatchat.common.mcp.service;

import java.util.Map;
import java.util.LinkedHashMap;

/** Protocol-level cursor request; providers expose it to tools as pageToken/pageSize. */
public record McpPaginationRequest(String pageToken, Integer pageSize) {
    public McpPaginationRequest {
        pageToken = pageToken == null || pageToken.isBlank() ? null : pageToken;
        if (pageSize != null && pageSize < 1) throw new IllegalArgumentException("pageSize must be positive");
    }

    public static McpPaginationRequest from(Map<String, Object> arguments) {
        if (arguments == null) return null;
        Object token = arguments.get("pageToken");
        Object size = arguments.get("pageSize");
        if (token == null && size == null) return null;
        Integer parsed = null;
        if (size != null) {
            try { parsed = Integer.valueOf(String.valueOf(size)); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("pageSize must be an integer"); }
        }
        return new McpPaginationRequest(token == null ? null : String.valueOf(token), parsed);
    }

    /** Adds the standard optional cursor inputs without changing producer-owned fields. */
    public static Map<String, Object> augmentInputSchema(Map<String, Object> inputSchema) {
        Map<String, Object> schema = new LinkedHashMap<>(inputSchema == null ? Map.of() : inputSchema);
        schema.putIfAbsent("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Object declared = schema.get("properties");
        if (declared instanceof Map<?, ?> source) {
            source.forEach((key, value) -> { if (key != null) properties.put(String.valueOf(key), value); });
        }
        properties.putIfAbsent("pageToken", Map.of("type", "string",
            "description", "Opaque continuation token returned by the preceding MCP result"));
        properties.putIfAbsent("pageSize", Map.of("type", "integer", "minimum", 1,
            "description", "Maximum records requested for this result page"));
        schema.put("properties", Map.copyOf(properties));
        return Map.copyOf(schema);
    }
}
