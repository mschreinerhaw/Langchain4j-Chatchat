package com.chatchat.common.tool;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Publisher-owned workflow contract. Declared metadata is authoritative;
 * tool-name inference exists only for one-time migration of pre-v1 catalogs.
 */
public final class ToolWorkflowContract {

    public static final String SCHEMA_VERSION = "tool_workflow_contract.v1";
    public static final String METADATA_KEY = "workflowContract";

    private ToolWorkflowContract() {
    }

    public static Optional<ToolWorkflowRole> declaredRole(ToolMetadata metadata) {
        Map<String, Object> contract = contractMap(metadata);
        return parseRole(first(contract, "workflowRole", "workflow_role", "role"));
    }

    public static ToolWorkflowRole resolveRole(String toolName, ToolMetadata metadata) {
        return declaredRole(metadata).orElseGet(() -> legacyRole(toolName));
    }

    public static boolean isDeclared(ToolMetadata metadata) {
        return declaredRole(metadata).isPresent();
    }

    public static Optional<String> declaredProtocolFamily(ToolMetadata metadata) {
        return Optional.ofNullable(text(first(contractMap(metadata), "protocolFamily", "protocol_family")));
    }

    public static Optional<String> declaredInputEnvelope(ToolMetadata metadata) {
        return Optional.ofNullable(text(first(contractMap(metadata), "inputEnvelope", "input_envelope")));
    }

    public static void validate(String toolName, ToolMetadata metadata) {
        Map<String, Object> contract = contractMap(metadata);
        if (contract.isEmpty()) {
            return;
        }
        String schemaVersion = text(first(contract, "schemaVersion", "schema_version"));
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported tool workflow contract schema for " + toolName
                + ": " + schemaVersion);
        }
        declaredRole(metadata).orElseThrow(() ->
            new IllegalArgumentException("Tool workflowContract.workflowRole is required: " + toolName));
    }

    private static ToolWorkflowRole legacyRole(String toolName) {
        if (McpToolNamePolicy.isAssetDiscovery(toolName)) return ToolWorkflowRole.ASSET_DISCOVERY;
        if (McpToolNamePolicy.isTemplateDiscovery(toolName)) return ToolWorkflowRole.TEMPLATE_DISCOVERY;
        if (McpToolNamePolicy.isTemplateExecution(toolName)) return ToolWorkflowRole.TEMPLATE_EXECUTION;
        return ToolWorkflowRole.DIRECT;
    }

    private static Map<String, Object> contractMap(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) return Map.of();
        Object value = metadata.getMetadata().get(METADATA_KEY);
        if (value == null && metadata.getMetadata().get("mcpToolMeta") instanceof Map<?, ?> nested) {
            value = nested.get(METADATA_KEY);
        }
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return result;
    }

    private static Optional<ToolWorkflowRole> parseRole(Object value) {
        String role = text(value);
        if (role == null) return Optional.empty();
        try {
            return Optional.of(ToolWorkflowRole.valueOf(role.toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported tool workflow role: " + role, ex);
        }
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
