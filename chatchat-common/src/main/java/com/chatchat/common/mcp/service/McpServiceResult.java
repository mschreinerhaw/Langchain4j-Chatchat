package com.chatchat.common.mcp.service;

import java.util.LinkedHashMap;
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
    McpResultKind resultKind,
    String resultSchemaRef,
    McpResultProvenance provenance,
    McpPaginationResult pagination,
    long completedAt
) {
    public static final String SCHEMA_VERSION = "mcp_service_result.v1";
    public static final String RESULT_KIND_KEY = "resultKind";
    public static final String RESULT_SCHEMA_REF_KEY = "resultSchemaRef";
    public static final String PROVENANCE_KEY = "provenance";
    public static final String PAGINATION_KEY = "pagination";

    public McpServiceResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported MCP service result schema: " + schemaVersion);
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("serviceId is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is required");
        status = status == null ? McpServiceResultStatus.FAILED : status;
        boolean successful = status == McpServiceResultStatus.SUCCESS
            || status == McpServiceResultStatus.REPAIRED
            || status == McpServiceResultStatus.PARTIAL
            || status == McpServiceResultStatus.EMPTY_RESULT;
        if (!successful) {
            errorCode = text(errorCode) == null ? "MCP_" + status.name() : errorCode.trim();
            errorMessage = text(errorMessage) == null
                ? "MCP invocation failed with status " + status.name() + " (" + errorCode + ")"
                : errorMessage.trim();
            if (rawData == null) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("schemaVersion", "mcp_failure_evidence.v1");
                failure.put("status", status.name());
                failure.put("errorCode", errorCode);
                failure.put("errorMessage", errorMessage);
                failure.put("retryable", retryable);
                if (text(recoveryAction) != null) failure.put("recoveryAction", recoveryAction.trim());
                rawData = Map.copyOf(failure);
            }
        }
        metadata = McpServiceDescriptor.immutable(metadata);
        resultKind = resultKind == null ? McpResultKind.parse(metadata.get(RESULT_KIND_KEY)) : resultKind;
        if (status == McpServiceResultStatus.EMPTY_RESULT) resultKind = McpResultKind.EMPTY;
        resultSchemaRef = resultSchemaRef == null || resultSchemaRef.isBlank()
            ? text(metadata.get(RESULT_SCHEMA_REF_KEY)) : resultSchemaRef.trim();
        provenance = provenance == null ? McpResultProvenance.from(metadata.get(PROVENANCE_KEY)) : provenance;
        pagination = pagination == null ? McpPaginationResult.from(metadata.get(PAGINATION_KEY)) : pagination;
        completedAt = completedAt <= 0 ? System.currentTimeMillis() : completedAt;
    }

    public McpServiceResult(String schemaVersion, String requestId, String serviceId, String toolName,
                            McpServiceResultStatus status, Object data, Object rawData, String errorCode,
                            String errorMessage, boolean retryable, String recoveryAction,
                            Map<String, Object> metadata, long completedAt) {
        this(schemaVersion, requestId, serviceId, toolName, status, data, rawData, errorCode,
            errorMessage, retryable, recoveryAction, metadata, null, null, null, null, completedAt);
    }

    public boolean successful() {
        return status == McpServiceResultStatus.SUCCESS || status == McpServiceResultStatus.REPAIRED
            || status == McpServiceResultStatus.PARTIAL || status == McpServiceResultStatus.EMPTY_RESULT;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
