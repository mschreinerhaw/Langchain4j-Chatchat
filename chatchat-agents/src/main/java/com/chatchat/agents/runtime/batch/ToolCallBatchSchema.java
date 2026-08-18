package com.chatchat.agents.runtime.batch;

import com.chatchat.common.tool.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.List;
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
        return ToolExecutionCapabilities.supportsBatch(toolName, null);
    }

    public static boolean supports(String toolName, ToolMetadata metadata) {
        return ToolExecutionCapabilities.supportsBatch(toolName, metadata);
    }

    public static Map<String, Object> augment(String toolName, Map<String, Object> originalSchema) {
        Map<String, Object> original = originalSchema == null
            ? Map.of()
            : new LinkedHashMap<>(originalSchema);
        if (!supports(toolName)) {
            return original;
        }
        return augmentDeclared(original);
    }

    /** Adds the batch transport schema after capability admission has already succeeded. */
    public static Map<String, Object> augmentDeclared(Map<String, Object> originalSchema) {
        Map<String, Object> original = originalSchema == null
            ? Map.of()
            : new LinkedHashMap<>(originalSchema);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description",
            "Accepts the normal single-call input or a runtime-managed failure-isolated ordered template batch.");
        schema.put("anyOf", List.of(original, batchSchema()));
        schema.put(ToolExecutionCapabilities.BATCH_SCHEMA_EXTENSION, Map.of(
            "capability", ToolExecutionCapabilities.BATCH_EXECUTION,
            "governance", ToolExecutionCapabilities.TEMPLATE_EXECUTION,
            "executionMode", "SEQUENTIAL",
            "maxCalls", DEFAULT_MAX_CALLS,
            "maxPayloadBytes", DEFAULT_MAX_PAYLOAD_BYTES,
            "nestedBatchAllowed", false,
            "failureIsolation", true
        ));
        return schema;
    }

    public static Map<String, Object> batchSchema() {
        Map<String, Object> callSchema = new LinkedHashMap<>();
        callSchema.put("type", "object");
        callSchema.put("additionalProperties", false);
        callSchema.put("required", List.of("callId", "toolName", "arguments"));
        Map<String, Object> callProperties = new LinkedHashMap<>();
        callProperties.put("callId", Map.of("type", "string", "minLength", 1, "maxLength", 128));
        callProperties.put("toolName", Map.of(
                "type", "string",
                "description", "A registered template batch-capable executor from the Agent allow-list."
            ));
        callProperties.put("emptyResultIsSuccess", Map.of(
                "type", "boolean",
                "description", "Template-declared evidence policy. True only when an empty result is a valid diagnostic outcome."
            ));
        callProperties.put("requiredFields", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Backward-compatible alias for requiredMetrics; missing fields reduce evidence quality, not execution coverage."
            ));
        callProperties.put("purpose", Map.of(
                "type", "string",
                "description", "Template-owned purpose such as inventory, monitor, health, or root_cause."
            ));
        callProperties.put("healthCapability", Map.of(
                "type", "boolean",
                "description", "Whether the template can directly support a health assessment."
            ));
        callProperties.put("requiredMetrics", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Metrics required for complete assessment quality. Missing metrics do not turn a successful invocation into a failed invocation."
            ));
        callProperties.put("timeSemantics", Map.of(
                "type", "string",
                "description", "Metric time meaning, for example POINT_IN_TIME, SAMPLE_WINDOW, or SINCE_INSTANCE_START."
            ));
        callProperties.put("requiresContext", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Context metrics required before values may support a strong conclusion."
            ));
        callProperties.put("freshnessMaxAgeSeconds", Map.of(
                "type", "integer",
                "minimum", 0,
                "description", "Maximum accepted observation age when a timestamp is available."
            ));
        callProperties.put("arguments", Map.of(
                "type", "object",
                "description", "The authorized input schema for the selected governed template."
            ));
        callSchema.put("properties", callProperties);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("executionMode", "calls"));
        schema.put("properties", Map.of(
            "batchId", Map.of("type", "string", "minLength", 1, "maxLength", 128),
            "executionMode", Map.of("type", "string", "enum", List.of("SEQUENTIAL")),
            "stopOnFailure", Map.of(
                "type", "boolean",
                "default", false,
                "description", "Compatibility field. Template child failures are always isolated and never stop later calls."
            ),
            "calls", Map.of(
                "type", "array",
                "minItems", 1,
                "maxItems", DEFAULT_MAX_CALLS,
                "items", callSchema
            )
        ));
        return schema;
    }
}
