package com.chatchat.mcpserver.tool;

import java.util.Locale;

/** Lifecycle state of a materialized MCP tool publication. */
public enum McpToolPublicationStatus {
    DRAFT,
    VALIDATED,
    ACTIVE,
    DEPRECATED,
    DISABLED,
    RETIRED;

    public boolean publishable() {
        return this == ACTIVE || this == DEPRECATED;
    }

    public static McpToolPublicationStatus parse(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return ACTIVE;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported MCP publication status: " + value, invalid);
        }
    }
}
