package com.chatchat.common.knowledge.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete, transport-neutral outcome returned by a governed template service. */
public record TemplateServiceResult(
    String schemaVersion,
    String requestId,
    TemplateServiceOperation operation,
    TemplateServiceResultStatus status,
    Map<String, Object> data,
    List<TemplateResolutionEvent> events,
    boolean retryable,
    Map<String, Object> metadata,
    long completedAt
) {
    public static final String SCHEMA_VERSION = "template_service_result.v1";

    public TemplateServiceResult {
        schemaVersion = clean(schemaVersion, SCHEMA_VERSION);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported template service result schema: " + schemaVersion);
        }
        requestId = clean(requestId, null);
        if (requestId == null) throw new IllegalArgumentException("template service result requestId is required");
        if (operation == null) throw new IllegalArgumentException("template service result operation is required");
        status = status == null ? TemplateServiceResultStatus.FAILED : status;
        data = immutable(data);
        events = events == null ? List.of() : events.stream().filter(java.util.Objects::nonNull).toList();
        metadata = immutable(metadata);
        completedAt = completedAt <= 0 ? System.currentTimeMillis() : completedAt;
    }

    public boolean successful() {
        return status == TemplateServiceResultStatus.SUCCESS || status == TemplateServiceResultStatus.EMPTY;
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
