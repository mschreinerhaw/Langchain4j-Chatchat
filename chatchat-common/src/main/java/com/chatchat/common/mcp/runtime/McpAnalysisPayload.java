package com.chatchat.common.mcp.runtime;

import com.chatchat.common.mcp.service.McpServiceResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Lossless model-facing MCP payload containing both normalized and original data. */
public record McpAnalysisPayload(
    String schemaVersion,
    String requestId,
    String serviceId,
    String toolName,
    String status,
    Object data,
    Object rawData,
    Map<String, Object> runtimeMetadata
) {
    public static final String SCHEMA_VERSION = "mcp_analysis_payload.v1";

    public McpAnalysisPayload {
        schemaVersion = SCHEMA_VERSION;
        runtimeMetadata = runtimeMetadata == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(runtimeMetadata));
    }

    public static McpAnalysisPayload from(McpServiceResult result, Object governedData) {
        return from(result, governedData, result == null ? null : result.rawData());
    }

    public static McpAnalysisPayload from(McpServiceResult result, Object governedData, Object governedRawData) {
        if (result == null) throw new IllegalArgumentException("result is required");
        return new McpAnalysisPayload(null, result.requestId(), result.serviceId(), result.toolName(),
            result.status().name(), governedData, governedRawData, result.metadata());
    }

    /** Stable map form used by existing model and evidence serializers. */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("requestId", requestId);
        value.put("serviceId", serviceId);
        value.put("toolName", toolName);
        value.put("status", status);
        value.put("data", data);
        value.put("rawData", rawData);
        value.put("runtimeMetadata", runtimeMetadata);
        return value;
    }
}
