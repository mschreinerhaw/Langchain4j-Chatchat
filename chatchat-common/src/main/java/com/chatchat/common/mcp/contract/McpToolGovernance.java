package com.chatchat.common.mcp.contract;

import com.chatchat.common.kernel.KernelDataBoundary;
import com.chatchat.common.kernel.KernelProtocolCatalog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mandatory governance policy carried by every MCP tool contract. */
public record McpToolGovernance(
    String riskLevel,
    String operationType,
    String runtimeLevel,
    boolean confirmationRequired,
    Map<String, Object> permissions,
    Map<String, Object> inputPolicy,
    Map<String, Object> outputPolicy,
    KernelDataBoundary dataBoundary
) {
    public McpToolGovernance {
        riskLevel = required(riskLevel, "riskLevel");
        operationType = required(operationType, "operationType");
        runtimeLevel = required(runtimeLevel, "runtimeLevel");
        permissions = immutable(permissions);
        inputPolicy = immutable(inputPolicy);
        outputPolicy = immutable(outputPolicy);
        dataBoundary = dataBoundary == null ? KernelProtocolCatalog.MCP_BOUNDARY : dataBoundary;
    }

    public static McpToolGovernance readOnly() {
        return new McpToolGovernance("low", "read", "readonly", false,
            Map.of(), Map.of(), Map.of("preservePayload", true), KernelProtocolCatalog.MCP_BOUNDARY);
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
