package com.chatchat.common.bridge.api;

import com.chatchat.common.knowledge.template.TemplateResolutionEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical API service response returned to MCP without replacing the business payload. */
public record McpApiResult(
    String schemaVersion,
    String requestId,
    McpApiOperation operation,
    McpApiResultStatus status,
    Map<String, Object> data,
    List<TemplateResolutionEvent> events,
    boolean retryable,
    Map<String, Object> metadata,
    long completedAt
) {
    public static final String SCHEMA_VERSION = "mcp_api_result.v1";

    public McpApiResult {
        schemaVersion = clean(schemaVersion, SCHEMA_VERSION);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported MCP/API result schema: " + schemaVersion);
        }
        requestId = clean(requestId, null);
        if (requestId == null) throw new IllegalArgumentException("MCP/API result requestId is required");
        if (operation == null) throw new IllegalArgumentException("MCP/API result operation is required");
        status = status == null ? McpApiResultStatus.FAILED : status;
        data = immutable(data);
        events = events == null ? List.of() : events.stream().filter(java.util.Objects::nonNull).toList();
        metadata = immutable(metadata);
        completedAt = completedAt <= 0 ? System.currentTimeMillis() : completedAt;
    }

    public boolean successful() {
        return status == McpApiResultStatus.SUCCESS || status == McpApiResultStatus.EMPTY;
    }

    /** JSON-ready compatibility projection used by MCP transports and legacy API consumers. */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("communicationSchemaVersion", schemaVersion);
        payload.put("communicationRequestId", requestId);
        payload.put("communicationOperation", operation.operationCode());
        payload.put("communicationStatus", status.name());
        payload.put("events", events);
        payload.put("retryable", retryable);
        return Collections.unmodifiableMap(payload);
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
