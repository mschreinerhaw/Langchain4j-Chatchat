package com.chatchat.common.mcp.service;

import java.util.Map;
import java.util.UUID;

/** Canonical invocation envelope accepted by every dynamically injected MCP provider. */
public record McpServiceCall(
    String schemaVersion,
    String requestId,
    String serviceId,
    String toolName,
    Map<String, Object> arguments,
    Map<String, Object> context,
    long deadlineAt
) {
    public static final String SCHEMA_VERSION = "mcp_service_call.v1";

    public McpServiceCall {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported MCP service call schema: " + schemaVersion);
        requestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("serviceId is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is required");
        serviceId = serviceId.trim();
        toolName = toolName.trim();
        arguments = McpServiceDescriptor.immutable(arguments);
        context = McpServiceDescriptor.immutable(context);
        deadlineAt = Math.max(0, deadlineAt);
    }

    public boolean expired(long now) { return deadlineAt > 0 && now > deadlineAt; }

    public McpServiceCall withContext(Map<String, Object> governedContext) {
        return new McpServiceCall(schemaVersion, requestId, serviceId, toolName, arguments,
            governedContext, deadlineAt);
    }
}
