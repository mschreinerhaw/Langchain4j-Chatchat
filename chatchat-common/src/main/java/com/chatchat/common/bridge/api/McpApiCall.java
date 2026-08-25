package com.chatchat.common.bridge.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical MCP to API call payload. Transport, URL and HTTP implementation details are deliberately
 * excluded: an MCP caller selects a governed template and supplies only schema-declared parameters.
 */
public record McpApiCall(
    String schemaVersion,
    McpApiOperation operation,
    String caller,
    String targetService,
    String query,
    String templateId,
    Map<String, Object> filters,
    Map<String, Object> parameters,
    Map<String, Object> context,
    Map<String, Object> extensions,
    String idempotencyKey,
    long deadlineAt
) {
    public static final String SCHEMA_VERSION = "mcp_api_call.v1";

    public McpApiCall {
        schemaVersion = clean(schemaVersion, SCHEMA_VERSION);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported MCP/API call schema: " + schemaVersion);
        }
        if (operation == null) throw new IllegalArgumentException("MCP/API operation is required");
        caller = clean(caller, "mcp-runtime");
        targetService = clean(targetService, "api-service");
        query = clean(query, null);
        templateId = clean(templateId, null);
        filters = immutable(filters);
        parameters = immutable(parameters);
        context = immutable(context);
        extensions = immutable(extensions);
        rejectTransportDefinitions(extensions);
        idempotencyKey = clean(idempotencyKey, null);
        deadlineAt = Math.max(0, deadlineAt);
    }

    public static McpApiCall search(String query, Map<String, Object> filters,
                                    Map<String, Object> context, Map<String, Object> extensions) {
        return new McpApiCall(SCHEMA_VERSION, McpApiOperation.TEMPLATE_SEARCH, "mcp-runtime",
            "api-service", query, null, filters, Map.of(), context, extensions, null, 0);
    }

    public static McpApiCall execute(String templateId, Map<String, Object> parameters,
                                     Map<String, Object> context, String idempotencyKey, long deadlineAt) {
        return new McpApiCall(SCHEMA_VERSION, McpApiOperation.TEMPLATE_EXECUTE, "mcp-runtime",
            "api-service", null, templateId, Map.of(), parameters, context, Map.of(),
            idempotencyKey, deadlineAt);
    }

    public boolean expired(long now) {
        return deadlineAt > 0 && now > deadlineAt;
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static void rejectTransportDefinitions(Map<String, Object> extensions) {
        for (String key : extensions.keySet()) {
            String normalized = key == null ? "" : key.replace("_", "").toLowerCase(java.util.Locale.ROOT);
            if (java.util.Set.of("url", "urltemplate", "method", "headers", "headersjson",
                "body", "bodytemplate").contains(normalized)) {
                throw new IllegalArgumentException(
                    "Raw API transport definition is forbidden on the MCP/API bridge: " + key);
            }
        }
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
