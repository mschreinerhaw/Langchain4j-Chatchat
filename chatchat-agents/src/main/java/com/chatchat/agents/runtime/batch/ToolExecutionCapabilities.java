package com.chatchat.agents.runtime.batch;

import com.chatchat.common.tool.ToolMetadata;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/** Resolves runtime execution capabilities from tool-owned metadata. */
public final class ToolExecutionCapabilities {

    public static final String BATCH_EXECUTION = "batch_execution";
    public static final String TEMPLATE_EXECUTION = "template_execution";
    public static final String BATCH_SCHEMA_EXTENSION = "x-chatchat-batch";

    private ToolExecutionCapabilities() {
    }

    public static boolean supportsBatch(String toolName, ToolMetadata metadata) {
        if (declaresBatch(metadata)) {
            return declaresTemplateExecution(metadata);
        }
        return LegacyBatchExecutorCapabilityAdapter.supports(toolName);
    }

    public static boolean declaresBatch(ToolMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        if (containsCapability(metadata.getTags()) || containsCapability(metadata.getCategories())) {
            return true;
        }
        return declaresBatch(metadata.getMetadata());
    }

    public static boolean declaresTemplateExecution(ToolMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        if (containsCapability(metadata.getTags(), TEMPLATE_EXECUTION)
            || containsCapability(metadata.getCategories(), TEMPLATE_EXECUTION)) {
            return true;
        }
        Map<String, Object> values = metadata.getMetadata();
        if (values == null || values.isEmpty()) {
            return false;
        }
        if (values.get("inputSchema") instanceof Map<?, ?> schema
            && schema.containsKey(BATCH_SCHEMA_EXTENSION)) {
            return true;
        }
        return truthy(values.get(TEMPLATE_EXECUTION))
            || truthy(values.get("templateExecution"))
            || containsCapability(values.get("capabilities"), TEMPLATE_EXECUTION)
            || containsCapability(values.get("governanceContracts"), TEMPLATE_EXECUTION);
    }

    public static boolean declaresBatch(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        if (truthy(metadata.get("batchCapable")) || truthy(metadata.get("supportsBatch"))
            || truthy(metadata.get(BATCH_EXECUTION))) {
            return true;
        }
        if (metadata.containsKey(BATCH_SCHEMA_EXTENSION)) {
            return true;
        }
        if (containsCapability(metadata.get("capabilities"), BATCH_EXECUTION)) {
            return true;
        }
        Object inputSchema = metadata.get("inputSchema");
        return inputSchema instanceof Map<?, ?> schema && schema.containsKey(BATCH_SCHEMA_EXTENSION);
    }

    private static boolean containsCapability(Object value) {
        return containsCapability(value, BATCH_EXECUTION);
    }

    private static boolean containsCapability(Object value, String expected) {
        if (value instanceof Collection<?> values) {
            return values.stream().anyMatch(item -> containsCapability(item, expected));
        }
        if (value instanceof Map<?, ?> values) {
            String camelCase = TEMPLATE_EXECUTION.equals(expected) ? "templateExecution" : "batchExecution";
            return truthy(values.get(expected))
                || truthy(values.get(camelCase))
                || values.keySet().stream().anyMatch(item -> containsCapability(item, expected));
        }
        String normalized = normalize(value);
        if (expected.equals(normalized)) {
            return true;
        }
        return BATCH_EXECUTION.equals(expected) && "batch_executor".equals(normalized);
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value)
            || "true".equalsIgnoreCase(value == null ? "" : String.valueOf(value).trim());
    }

    private static String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
