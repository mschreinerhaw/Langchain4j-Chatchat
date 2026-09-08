package com.chatchat.common.mcp.capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Declares which tool owns template candidate selection for one asset family.
 *
 * <p>A fixed binding is an authoritative projection of persisted governance
 * relationships. It therefore replaces open-ended discovery for the same asset
 * family inside one execution plan; it is not another ranked candidate source.</p>
 */
public record McpTemplateSelectionScope(
    String contractVersion,
    String assetType,
    String selectionMode,
    boolean authoritative
) {
    public static final String METADATA_KEY = "templateSelectionScope";
    public static final String CURRENT_VERSION = "mcp.template-selection-scope.v1";
    public static final String FIXED_BINDING = "FIXED_BINDING";

    public McpTemplateSelectionScope {
        contractVersion = required(contractVersion, "contractVersion");
        assetType = required(assetType, "assetType");
        selectionMode = required(selectionMode, "selectionMode");
    }

    public static McpTemplateSelectionScope fixedBinding(String assetType) {
        return new McpTemplateSelectionScope(CURRENT_VERSION, assetType, FIXED_BINDING, true);
    }

    public boolean fixedBindingAuthority() {
        return authoritative && FIXED_BINDING.equalsIgnoreCase(selectionMode);
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("contractVersion", contractVersion);
        values.put("assetType", assetType);
        values.put("selectionMode", selectionMode);
        values.put("authoritative", authoritative);
        return Collections.unmodifiableMap(values);
    }

    public static Optional<McpTemplateSelectionScope> fromToolMetadata(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get(METADATA_KEY) instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        Map<String, Object> values = cast(raw);
        String version = text(values.get("contractVersion"));
        if (!CURRENT_VERSION.equals(version)) {
            return Optional.empty();
        }
        String assetType = text(values.get("assetType"));
        String selectionMode = text(values.get("selectionMode"));
        if (assetType == null || selectionMode == null) {
            return Optional.empty();
        }
        return Optional.of(new McpTemplateSelectionScope(
            version, assetType, selectionMode, Boolean.TRUE.equals(values.get("authoritative"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> values) {
        return new LinkedHashMap<>((Map<String, Object>) values);
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
