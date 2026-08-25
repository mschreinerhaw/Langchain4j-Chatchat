package com.chatchat.common.mcp.service;

import java.util.Map;

/** Outcome of deterministic result repair; rawResult is never discarded. */
public record McpResultRepairResult(
    String schemaVersion,
    String requestId,
    String serviceId,
    String toolName,
    McpServiceResultStatus status,
    Object normalizedData,
    Object rawResult,
    Map<String, Object> diagnostics,
    String message
) {
    public static final String SCHEMA_VERSION = "mcp_result_repair_result.v1";

    public McpResultRepairResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported MCP repair result schema: " + schemaVersion);
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("serviceId is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is required");
        diagnostics = McpServiceDescriptor.immutable(diagnostics);
        status = status == null ? McpServiceResultStatus.FAILED : status;
    }
}
