package com.chatchat.common.mcp.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Replay and audit identity for the data observed by one MCP invocation. */
public record McpResultProvenance(
    String sourceRef,
    String dataVersion,
    String asOf,
    String inputFingerprint,
    Map<String, Object> rowRange,
    Map<String, Object> filterSummary
) {
    public McpResultProvenance {
        sourceRef = clean(sourceRef);
        dataVersion = clean(dataVersion);
        asOf = clean(asOf);
        inputFingerprint = clean(inputFingerprint);
        rowRange = McpServiceDescriptor.immutable(rowRange);
        filterSummary = McpServiceDescriptor.immutable(filterSummary);
    }

    public static McpResultProvenance from(Object value) {
        if (value instanceof McpResultProvenance provenance) return provenance;
        if (!(value instanceof Map<?, ?> source)) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) map.put(String.valueOf(key), item); });
        return new McpResultProvenance(text(map.get("sourceRef")), text(map.get("dataVersion")),
            text(map.get("asOf")), text(map.get("inputFingerprint")), map(map.get("rowRange")),
            map(map.get("filterSummary")));
    }

    public boolean declared() {
        return sourceRef != null || dataVersion != null || asOf != null || inputFingerprint != null
            || !rowRange.isEmpty() || !filterSummary.isEmpty();
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return result;
    }
}
