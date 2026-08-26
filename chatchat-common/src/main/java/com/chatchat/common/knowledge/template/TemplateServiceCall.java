package com.chatchat.common.knowledge.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Transport-neutral command accepted by every governed template service adapter. */
public record TemplateServiceCall(
    String schemaVersion,
    TemplateServiceOperation operation,
    String query,
    String templateId,
    Map<String, Object> filters,
    Map<String, Object> parameters,
    Map<String, Object> context,
    Map<String, Object> extensions,
    String idempotencyKey,
    long deadlineAt
) {
    public static final String SCHEMA_VERSION = "template_service_call.v1";

    public TemplateServiceCall {
        schemaVersion = clean(schemaVersion, SCHEMA_VERSION);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported template service call schema: " + schemaVersion);
        }
        if (operation == null) throw new IllegalArgumentException("template service operation is required");
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

    public static TemplateServiceCall search(String query, Map<String, Object> filters,
                                             Map<String, Object> context,
                                             Map<String, Object> extensions) {
        return new TemplateServiceCall(SCHEMA_VERSION, TemplateServiceOperation.SEARCH, query, null,
            filters, Map.of(), context, extensions, null, 0);
    }

    public static TemplateServiceCall execute(String templateId, Map<String, Object> parameters,
                                              Map<String, Object> context,
                                              String idempotencyKey, long deadlineAt) {
        return new TemplateServiceCall(SCHEMA_VERSION, TemplateServiceOperation.EXECUTE, null, templateId,
            Map.of(), parameters, context, Map.of(), idempotencyKey, deadlineAt);
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
            String normalized = key == null ? "" : key.replace("_", "").toLowerCase(Locale.ROOT);
            if (Set.of("url", "urltemplate", "method", "headers", "headersjson",
                "body", "bodytemplate").contains(normalized)) {
                throw new IllegalArgumentException(
                    "Raw transport definition is forbidden on the template service port: " + key);
            }
        }
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
