package com.chatchat.mcpserver.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descriptive MCP tool applicability metadata shared by management UIs and models.
 * It is deliberately not an execution policy and must never select, add or replace tools.
 */
public final class McpToolApplicability {

    public static final String META_KEY = "applicability";
    public static final String SCHEMA_VERSION = "tool_applicability.v1";

    private McpToolApplicability() {
    }

    public static Map<String, Object> of(String scopeId,
                                         String scopeLabel,
                                         List<String> backendServiceTypes,
                                         String summary,
                                         List<String> useWhen,
                                         List<String> notFor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("scopeId", scopeId);
        value.put("scopeLabel", scopeLabel);
        value.put("backendServiceTypes", backendServiceTypes == null ? List.of() : List.copyOf(backendServiceTypes));
        value.put("summary", summary);
        value.put("useWhen", useWhen == null ? List.of() : List.copyOf(useWhen));
        value.put("notFor", notFor == null ? List.of() : List.copyOf(notFor));
        value.put("descriptiveOnly", true);
        value.put("maySelectOrReplaceTool", false);
        return Map.copyOf(value);
    }
}
