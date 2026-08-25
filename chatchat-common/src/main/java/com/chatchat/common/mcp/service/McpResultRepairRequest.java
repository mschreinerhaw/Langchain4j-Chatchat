package com.chatchat.common.mcp.service;

import java.util.Map;
import java.util.UUID;

/** Input used when model-side parsing failed and the raw MCP response must be normalized again. */
public record McpResultRepairRequest(
    String schemaVersion,
    String requestId,
    String serviceId,
    String toolName,
    Object rawResult,
    String parseError,
    Map<String, Object> expectedOutputSchema,
    Map<String, Object> context
) {
    public static final String SCHEMA_VERSION = "mcp_result_repair_request.v1";

    public McpResultRepairRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported MCP repair schema: " + schemaVersion);
        requestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("serviceId is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is required");
        expectedOutputSchema = McpServiceDescriptor.immutable(expectedOutputSchema);
        context = McpServiceDescriptor.immutable(context);
    }

    public McpResultRepairRequest withExpectedOutputSchema(Map<String, Object> schema) {
        return new McpResultRepairRequest(schemaVersion, requestId, serviceId, toolName, rawResult,
            parseError, schema, context);
    }
}
