package com.chatchat.common.mcp.capability;

/** Semantic position of a tool in the Runtime OS MCP capability tree. */
public enum McpCapabilityNodeKind {
    /** Stable protocol and routing abstraction; not preferred as business evidence. */
    ABSTRACT_CAPABILITY,
    /** Governed domain implementation published below an abstract capability. */
    BUSINESS_IMPLEMENTATION,
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
