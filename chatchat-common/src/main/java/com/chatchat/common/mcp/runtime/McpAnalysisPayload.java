package com.chatchat.common.mcp.runtime;

import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpPaginationResult;
import com.chatchat.common.mcp.service.McpResultKind;
import com.chatchat.common.mcp.service.McpResultProvenance;

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
    McpResultKind resultKind,
    String resultSchemaRef,
    McpResultProvenance provenance,
    McpPaginationResult pagination,
    Map<String, Object> completeness,
    Object data,
    Object rawData,
    Map<String, Object> runtimeMetadata
) {
    public static final String SCHEMA_VERSION = "mcp_analysis_payload.v1";

    public McpAnalysisPayload {
        schemaVersion = SCHEMA_VERSION;
        resultKind = resultKind == null ? McpResultKind.UNDECLARED : resultKind;
        completeness = completeness == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(completeness));
        runtimeMetadata = runtimeMetadata == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(runtimeMetadata));
    }

    public static McpAnalysisPayload from(McpServiceResult result, Object governedData) {
        return from(result, governedData, result == null ? null : result.rawData());
    }

    public static McpAnalysisPayload from(McpServiceResult result, Object governedData, Object governedRawData) {
        if (result == null) throw new IllegalArgumentException("result is required");
        return new McpAnalysisPayload(null, result.requestId(), result.serviceId(), result.toolName(),
            result.status().name(), result.resultKind(), result.resultSchemaRef(), result.provenance(),
            result.pagination(), completeness(result), governedData, governedRawData, result.metadata());
    }

    /** Stable map form used by existing model and evidence serializers. */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("requestId", requestId);
        value.put("serviceId", serviceId);
        value.put("toolName", toolName);
        value.put("status", status);
        value.put("resultKind", resultKind.name());
        if (resultSchemaRef != null) value.put("resultSchemaRef", resultSchemaRef);
        if (provenance != null) value.put("provenance", provenance);
        if (pagination != null) value.put("pagination", pagination);
        value.put("completeness", completeness);
        value.put("data", data);
        value.put("rawData", rawData);
        value.put("runtimeMetadata", runtimeMetadata);
        return value;
    }

    private static Map<String, Object> completeness(McpServiceResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", result.status().name());
        value.put("complete", result.status() == com.chatchat.common.mcp.service.McpServiceResultStatus.SUCCESS
            || result.status() == com.chatchat.common.mcp.service.McpServiceResultStatus.EMPTY_RESULT);
        Object automaticRepair = result.metadata().get("automaticRepair");
        value.put("repairApplied", automaticRepair != null
            || result.status() == com.chatchat.common.mcp.service.McpServiceResultStatus.REPAIRED);
        if (automaticRepair != null) value.put("repair", automaticRepair);
        if (result.status() == com.chatchat.common.mcp.service.McpServiceResultStatus.PARTIAL) {
            Object missing = result.metadata().get("missingRequired");
            if (missing == null && automaticRepair instanceof com.chatchat.common.mcp.service.McpResultRepairResult repair) {
                missing = repair.diagnostics().get("missingRequired");
            }
            value.put("missingRequired", missing == null ? java.util.List.of() : missing);
        }
        return Map.copyOf(value);
    }
}
