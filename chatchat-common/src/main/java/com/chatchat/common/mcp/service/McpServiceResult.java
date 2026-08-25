package com.chatchat.common.mcp.service;

import java.util.Map;

/** Result envelope that always preserves the original MCP result beside normalized data. */
public record McpServiceResult(
    String schemaVersion,
    String requestId,
    String serviceId,
    String toolName,
    McpServiceResultStatus status,
    Object data,
    Object rawData,
    String errorCode,
    String errorMessage,
    boolean retryable,
    String recoveryAction,
    Map<String, Object> metadata,
    long completedAt
) {
    public static final String SCHEMA_VERSION = "mcp_service_result.v1";

    public McpServiceResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported MCP service result schema: " + schemaVersion);
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("serviceId is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is required");
        status = status == null ? McpServiceResultStatus.FAILED : status;
        metadata = McpServiceDescriptor.immutable(metadata);
        completedAt = completedAt <= 0 ? System.currentTimeMillis() : completedAt;
    }

    public boolean successful() {
        return status == McpServiceResultStatus.SUCCESS || status == McpServiceResultStatus.REPAIRED
            || status == McpServiceResultStatus.PARTIAL;
    }
}
