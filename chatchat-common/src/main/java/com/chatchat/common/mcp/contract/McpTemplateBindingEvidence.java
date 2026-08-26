package com.chatchat.common.mcp.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Runtime-owned proof that a template executor identity came from governed discovery. */
public record McpTemplateBindingEvidence(
    String schemaVersion,
    String source,
    String templateId,
    String executorTool
) {
    public static final String SCHEMA_VERSION = "runtime_template_binding.v1";
    public static final String CONTEXT_KEY = "runtimeTemplateBinding";

    public McpTemplateBindingEvidence {
        schemaVersion = required(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported MCP template binding schema: " + schemaVersion);
        }
        source = required(source, "source");
        templateId = required(templateId, "templateId");
        executorTool = required(executorTool, "executorTool");
    }

    public static Optional<McpTemplateBindingEvidence> from(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Optional.empty();
        try {
            return Optional.of(new McpTemplateBindingEvidence(
                text(map.get("schemaVersion")), text(map.get("source")),
                text(map.get("templateId")), text(map.get("executorTool"))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public boolean authorizes(String expectedTemplateId, String expectedExecutorTool) {
        return templateId.equals(clean(expectedTemplateId))
            && executorTool.equals(clean(expectedExecutorTool));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("source", source);
        result.put("templateId", templateId);
        result.put("executorTool", executorTool);
        return Map.copyOf(result);
    }

    private static String required(String value, String field) {
        String result = clean(value);
        if (result == null) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
