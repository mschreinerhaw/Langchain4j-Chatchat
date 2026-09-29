package com.chatchat.common.tool;

import java.util.Map;

/** Publisher-declared purpose, independent of tool names, domains and transport. */
public enum ToolDataType {
    ASSET_QUERY, TEMPLATE_QUERY, DATA_FETCH, DOCUMENT_SEARCH, DIRECT_QA, ACTION_EXECUTION, UNKNOWN;

    public static final String METADATA_KEY = "data_type";

    public static String declared(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object value = metadata.get(METADATA_KEY);
        if (value == null && metadata.get("mcpToolMeta") instanceof Map<?, ?> nested)
            value = nested.get(METADATA_KEY);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    public static String declared(ToolMetadata metadata) {
        if (metadata == null) return null;
        return metadata.getDataType() != null && !metadata.getDataType().isBlank()
            ? metadata.getDataType().trim() : declared(metadata.getMetadata());
    }
}
