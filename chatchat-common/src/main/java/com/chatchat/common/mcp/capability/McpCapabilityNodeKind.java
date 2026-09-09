package com.chatchat.common.mcp.capability;

/** Semantic position of a tool in the Runtime OS MCP capability tree. */
public enum McpCapabilityNodeKind {
    /** Legacy protocol abstraction retained for reading older catalog snapshots. */
    ABSTRACT_CAPABILITY,
    /** Legacy child kind retained for reading older catalog snapshots. */
    BUSINESS_IMPLEMENTATION,
    /** A governed intersection/subset of its directly invocable parent capability. */
    SCOPED_SUBSET,
    /** Independent capability with no declared implementation relationship. */
    STANDALONE;

    public static McpCapabilityNodeKind parse(Object value, McpCapabilityNodeKind fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
