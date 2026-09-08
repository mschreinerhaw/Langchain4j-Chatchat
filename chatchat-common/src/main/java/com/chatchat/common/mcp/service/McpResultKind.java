package com.chatchat.common.mcp.service;

/** Producer-declared shape and evidence role of one MCP result. */
public enum McpResultKind {
    RAW_RECORDS,
    CALCULATION,
    DOCUMENT,
    COMMAND_STREAM,
    EMPTY,
    ERROR_PAGE,
    UNDECLARED;

    public static McpResultKind parse(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return UNDECLARED;
        try { return value instanceof McpResultKind kind ? kind
            : valueOf(String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return UNDECLARED; }
    }
}
