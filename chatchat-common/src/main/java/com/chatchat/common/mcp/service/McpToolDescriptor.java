package com.chatchat.common.mcp.service;

import java.util.Map;

/** Canonical tool contract projected by every MCP service provider. */
public record McpToolDescriptor(
    String serviceId,
    String localToolName,
    String remoteToolName,
    String description,
    String capabilityCode,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    Map<String, Object> governance,
    Map<String, Object> metadata,
    McpResultKind resultKind,
    String resultSchemaRef,
    boolean paginationSupported
) {
    public McpToolDescriptor {
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("serviceId is required");
        if (localToolName == null || localToolName.isBlank()) throw new IllegalArgumentException("localToolName is required");
        serviceId = serviceId.trim();
        localToolName = localToolName.trim();
        remoteToolName = remoteToolName == null || remoteToolName.isBlank() ? localToolName : remoteToolName.trim();
        description = description == null ? "" : description;
        capabilityCode = capabilityCode == null || capabilityCode.isBlank() ? "external" : capabilityCode.trim();
        inputSchema = McpServiceDescriptor.immutable(inputSchema);
        outputSchema = McpServiceDescriptor.immutable(outputSchema);
        governance = McpServiceDescriptor.immutable(governance);
        metadata = McpServiceDescriptor.immutable(metadata);
        resultKind = resultKind == null ? McpResultKind.parse(metadata.get(McpServiceResult.RESULT_KIND_KEY)) : resultKind;
        resultSchemaRef = resultSchemaRef == null || resultSchemaRef.isBlank()
            ? text(metadata.get(McpServiceResult.RESULT_SCHEMA_REF_KEY)) : resultSchemaRef.trim();
        paginationSupported = paginationSupported || Boolean.TRUE.equals(metadata.get("paginationSupported"));
    }

    public McpToolDescriptor(String serviceId, String localToolName, String remoteToolName,
                             String description, String capabilityCode, Map<String, Object> inputSchema,
                             Map<String, Object> outputSchema, Map<String, Object> governance,
                             Map<String, Object> metadata) {
        this(serviceId, localToolName, remoteToolName, description, capabilityCode, inputSchema,
            outputSchema, governance, metadata, null, null, false);
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
