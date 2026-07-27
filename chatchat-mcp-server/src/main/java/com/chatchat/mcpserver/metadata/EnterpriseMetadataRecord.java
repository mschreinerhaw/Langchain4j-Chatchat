package com.chatchat.mcpserver.metadata;

import java.util.LinkedHashMap;
import java.util.Map;

public record EnterpriseMetadataRecord(
    String id,
    String metadataType,
    String logicalIndex,
    String name,
    String technicalName,
    String description,
    String status,
    String source,
    Map<String, Object> attributes
) {
    public EnterpriseMetadataRecord {
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        put(value, "id", id);
        put(value, "metadataType", metadataType);
        put(value, "logicalIndex", logicalIndex);
        put(value, "name", name);
        put(value, "technicalName", technicalName);
        put(value, "description", description);
        put(value, "status", status);
        put(value, "source", source);
        value.putAll(attributes);
        return value;
    }

    private void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
