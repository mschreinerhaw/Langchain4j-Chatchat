package com.chatchat.common.knowledge.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Immutable template document used across Runtime OS communication boundaries. */
public record StandardTemplateKnowledge(
    String templateId,
    String templateType,
    String executorTool,
    String title,
    String summary,
    Map<String, Object> parameterSchema,
    Map<String, Object> outputSchema,
    List<String> requiredParameters,
    Map<String, Object> attributes
) implements TemplateKnowledge {
    public static final String SCHEMA_VERSION = "template_knowledge.v1";

    public StandardTemplateKnowledge {
        if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("templateId is required");
        templateId = templateId.trim();
        templateType = clean(templateType, "generic");
        executorTool = clean(executorTool, "");
        title = clean(title, templateId);
        summary = clean(summary, "");
        parameterSchema = immutable(parameterSchema);
        outputSchema = immutable(outputSchema);
        requiredParameters = requiredParameters == null ? List.of() : List.copyOf(requiredParameters);
        Map<String, Object> values = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        values.put("schemaVersion", SCHEMA_VERSION);
        attributes = Collections.unmodifiableMap(values);
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
