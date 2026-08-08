package com.chatchat.agents.protocol;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Stable MCP tool-name roles defined by the ChatChat system protocol. */
public enum McpToolProtocolRole {
    ASSET_QUERY("asset_query"),
    TEMPLATE_QUERY("template_query"),
    TEMPLATE_EXECUTE("template_execute");

    private final String suffix;

    McpToolProtocolRole(String suffix) {
        this.suffix = suffix;
    }

    public String suffix() {
        return suffix;
    }

    public boolean matches(String toolName) {
        String normalized = normalize(toolName);
        return normalized.equals(suffix) || normalized.endsWith("_" + suffix);
    }

    /** Returns the normalized tool-family prefix, or null when the role does not match. */
    public String family(String toolName) {
        String normalized = normalize(toolName);
        if (!matches(normalized)) {
            return null;
        }
        return normalized.substring(0, normalized.length() - suffix.length());
    }

    public static Optional<McpToolProtocolRole> resolve(String toolName) {
        return Arrays.stream(values()).filter(role -> role.matches(toolName)).findFirst();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
