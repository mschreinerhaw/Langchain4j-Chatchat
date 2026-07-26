package com.chatchat.agents.runtime.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Formal JSON Schema and shared safety defaults for runtime-managed MCP batches.
 */
public final class ToolCallBatchSchema {

    public static final int DEFAULT_MAX_CALLS = 32;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 262_144;

    private ToolCallBatchSchema() {
    }

    public static boolean supports(String toolName) {
        String normalized = toolName == null
            ? ""
            : toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return matches(normalized, "sql_query_execute")
            || matches(normalized, "ssh_linux_execute")
            || matches(normalized, "linux_command_execute")
            || matches(normalized, "api_query_execute")
            || matches(normalized, "api_template_execute")
            || matches(normalized, "http_request_execute");
    }

    public static Map<String, Object> augment(String toolName, Map<String, Object> originalSchema) {
        Map<String, Object> original = originalSchema == null
            ? Map.of()
            : new LinkedHashMap<>(originalSchema);
        if (!supports(toolName)) {
            return original;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description",
            "Accepts the normal single-call input or a runtime-managed ordered MCP batch.");
        schema.put("anyOf", List.of(original, batchSchema()));
        schema.put("x-chatchat-batch", Map.of(
            "executionMode", "SEQUENTIAL",
            "maxCalls", DEFAULT_MAX_CALLS,
            "maxPayloadBytes", DEFAULT_MAX_PAYLOAD_BYTES,
            "nestedBatchAllowed", false,
            "allowedToolFamilies", List.of(
                "sql_query_execute", "ssh_linux_execute", "api_query_execute")
        ));
        return schema;
    }

    public static Map<String, Object> batchSchema() {
        Map<String, Object> callSchema = new LinkedHashMap<>();
        callSchema.put("type", "object");
        callSchema.put("additionalProperties", false);
        callSchema.put("required", List.of("callId", "toolName", "arguments"));
        callSchema.put("properties", Map.of(
            "callId", Map.of("type", "string", "minLength", 1, "maxLength", 128),
            "toolName", Map.of(
                "type", "string",
                "description", "A registered SQL/SSH/API template executor from the Agent allow-list."
            ),
            "emptyResultIsSuccess", Map.of(
                "type", "boolean",
                "description", "Template-declared evidence policy. True only when an empty result is a valid diagnostic outcome."
            ),
            "arguments", Map.of(
                "type", "object",
                "description", "The normal authorized input schema for the selected child executor."
            )
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("executionMode", "calls"));
        schema.put("properties", Map.of(
            "batchId", Map.of("type", "string", "minLength", 1, "maxLength", 128),
            "executionMode", Map.of("type", "string", "enum", List.of("SEQUENTIAL")),
            "stopOnFailure", Map.of("type", "boolean", "default", false),
            "calls", Map.of(
                "type", "array",
                "minItems", 1,
                "maxItems", DEFAULT_MAX_CALLS,
                "items", callSchema
            )
        ));
        return schema;
    }

    private static boolean matches(String candidate, String semanticName) {
        return candidate.equals(semanticName) || candidate.endsWith("_" + semanticName);
    }
}
