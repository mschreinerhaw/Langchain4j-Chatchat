package com.chatchat.common.mcp.service;

import java.util.Set;

/** Transport-neutral filters for dynamic MCP contract lookup. */
public record McpToolQuery(String serviceId, String capabilityCode, Set<String> toolNames) {
    public McpToolQuery {
        serviceId = clean(serviceId);
        capabilityCode = clean(capabilityCode);
        toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
    }

    public static McpToolQuery all() { return new McpToolQuery(null, null, Set.of()); }

    public boolean matches(McpToolDescriptor tool) {
        return tool != null
            && (serviceId == null || serviceId.equals(tool.serviceId()))
            && (capabilityCode == null || capabilityCode.equalsIgnoreCase(tool.capabilityCode()))
            && (toolNames.isEmpty() || toolNames.contains(tool.localToolName())
                || toolNames.contains(tool.remoteToolName()));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
