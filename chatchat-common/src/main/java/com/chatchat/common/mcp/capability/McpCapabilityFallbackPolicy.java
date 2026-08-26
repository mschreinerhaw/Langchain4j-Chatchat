package com.chatchat.common.mcp.capability;

/** Behavior when an abstract capability has no selectable business implementation. */
public enum McpCapabilityFallbackPolicy {
    /** Legacy/general-purpose capability may execute as an independent tool. */
    ALLOW_STANDALONE,
    /** Abstract capability remains non-executable until an implementation is available. */
    DENY_WHEN_NO_IMPLEMENTATION;

    public static McpCapabilityFallbackPolicy parse(
        Object value, McpCapabilityFallbackPolicy fallback
    ) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
