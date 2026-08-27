package com.chatchat.common.tool;

import java.util.Locale;
import java.util.LinkedHashMap;
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

    /**
     * Builds the publisher-owned metadata envelope consumed by the persistent catalog.
     * Workflow publishers must declare their role; runtime code must not infer it from a tool name.
     */
    public static Map<String, Object> declaration(ToolWorkflowRole role,
                                                   String protocolFamily,
                                                   String inputEnvelope) {
        if (role == null) throw new IllegalArgumentException("workflow role is required");
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schemaVersion", SCHEMA_VERSION);
        contract.put("workflowRole", role.name());
        if (text(protocolFamily) != null) contract.put("protocolFamily", text(protocolFamily));
        if (text(inputEnvelope) != null) contract.put("inputEnvelope", text(inputEnvelope));
        return Map.copyOf(contract);
    }

    public static Optional<ToolWorkflowRole> declaredRole(ToolMetadata metadata) {
        Map<String, Object> contract = contractMap(metadata);
        return parseRole(first(contract, "workflowRole", "workflow_role", "role"));
    }

    public static ToolWorkflowRole resolveRole(String toolName, ToolMetadata metadata) {
        return declaredRole(metadata).orElseGet(() -> legacyRole(toolName));
    }

    /** Resolves workflow role from the canonical MCP descriptor metadata envelope. */
    public static ToolWorkflowRole resolveDescriptorRole(String toolName, Map<String, Object> metadata) {
        return declaredDescriptorRole(metadata).orElseGet(() -> legacyRole(toolName));
    }

    public static Optional<ToolWorkflowRole> declaredDescriptorRole(Map<String, Object> metadata) {
        return parseRole(first(contractMap(metadata), "workflowRole", "workflow_role", "role"));
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
        return metadata == null ? Map.of() : contractMap(metadata.getMetadata());
    }

    private static Map<String, Object> contractMap(Map<String, Object> metadata) {
        if (metadata == null) return Map.of();
        Object value = metadata.get(METADATA_KEY);
        if (value == null && metadata.get("mcpToolMeta") instanceof Map<?, ?> nested) {
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
