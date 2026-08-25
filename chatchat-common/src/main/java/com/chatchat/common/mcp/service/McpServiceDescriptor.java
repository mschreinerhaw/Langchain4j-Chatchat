package com.chatchat.common.mcp.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public, secret-free description of one dynamically available MCP service. */
public record McpServiceDescriptor(
    String serviceId,
    String name,
    String providerId,
    String transport,
    boolean enabled,
    Map<String, Object> metadata
) {
    public McpServiceDescriptor {
        serviceId = required(serviceId, "serviceId");
        name = required(name, "name");
        providerId = required(providerId, "providerId");
        transport = transport == null || transport.isBlank() ? "unknown" : transport.trim();
        metadata = immutable(metadata);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
