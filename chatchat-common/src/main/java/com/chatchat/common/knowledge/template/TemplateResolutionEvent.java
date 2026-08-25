package com.chatchat.common.knowledge.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

/** Structured, recoverable Runtime event for template selection and parameter gaps. */
public record TemplateResolutionEvent(
    String schemaVersion,
    String eventId,
    String requestId,
    TemplateResolutionEventType type,
    String templateId,
    List<String> missingParameters,
    TemplateRecoveryAction recoveryAction,
    boolean recoverable,
    String message,
    Map<String, Object> context,
    long occurredAt
) {
    public static final String SCHEMA_VERSION = "template_resolution_event.v1";

    public TemplateResolutionEvent {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        requestId = clean(requestId);
        templateId = clean(templateId);
        if (type == null) throw new IllegalArgumentException("template resolution event type is required");
        missingParameters = missingParameters == null ? List.of() : List.copyOf(missingParameters);
        if (recoveryAction == null) throw new IllegalArgumentException("template recovery action is required");
        message = message == null ? type.name() : message;
        context = context == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        occurredAt = occurredAt <= 0 ? System.currentTimeMillis() : occurredAt;
    }

    public static TemplateResolutionEvent missingId(String requestId, String searchTool) {
        return event(requestId, TemplateResolutionEventType.TEMPLATE_ID_MISSING, null, List.of(),
            TemplateRecoveryAction.SEARCH_TEMPLATE, "templateId is required",
            Map.of("searchTool", clean(searchTool) == null ? "" : clean(searchTool)));
    }

    public static TemplateResolutionEvent notFound(String requestId, String templateId, String searchTool) {
        return event(requestId, TemplateResolutionEventType.TEMPLATE_NOT_FOUND, templateId, List.of(),
            TemplateRecoveryAction.SEARCH_TEMPLATE, "Template was not found or is not enabled: " + templateId,
            Map.of("searchTool", clean(searchTool) == null ? "" : clean(searchTool)));
    }

    public static TemplateResolutionEvent searchEmpty(String requestId, String query, String searchTool) {
        return event(requestId, TemplateResolutionEventType.TEMPLATE_NOT_FOUND, null, List.of(),
            TemplateRecoveryAction.SEARCH_TEMPLATE, "No governed template matched the recall query",
            Map.of("query", clean(query) == null ? "" : clean(query),
                "searchTool", clean(searchTool) == null ? "" : clean(searchTool)));
    }

    public static TemplateResolutionEvent missingParameters(String requestId, String templateId,
                                                             List<String> missingParameters) {
        List<String> missing = missingParameters == null ? List.of() : List.copyOf(missingParameters);
        String message = missing.size() == 1
            ? "Template parameter is required: " + missing.get(0) + " for template " + templateId
                + ". Pass it under parameters." + missing.get(0)
            : "Template parameters are required: " + String.join(", ", missing) + " for template "
                + templateId + ". Pass them under parameters.";
        return event(requestId, TemplateResolutionEventType.TEMPLATE_PARAMETERS_MISSING, templateId,
            missing, TemplateRecoveryAction.REQUEST_PARAMETERS, message, Map.of());
    }

    private static TemplateResolutionEvent event(String requestId, TemplateResolutionEventType type,
                                                  String templateId, List<String> missing,
                                                  TemplateRecoveryAction action, String message,
                                                  Map<String, Object> context) {
        return new TemplateResolutionEvent(SCHEMA_VERSION, null, requestId, type, templateId, missing,
            action, true, message, context, System.currentTimeMillis());
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
