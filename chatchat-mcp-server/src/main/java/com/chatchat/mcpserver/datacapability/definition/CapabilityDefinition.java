package com.chatchat.mcpserver.datacapability.definition;

import java.util.Map;

/** Query definitions reference centrally managed datasource assets without copying connection settings. */
public record CapabilityDefinition(
    String code, String title, String description, CapabilityType type,
    String categoryId, String connectionId, String query,
    Map<String, Object> inputSchema, Map<String, String> resultMapping,
    Map<String, Object> options, int timeoutSeconds, int maxRows,
    boolean enabled, boolean apiPublished, boolean mcpPublished
) {
    public CapabilityDefinition {
        inputSchema = inputSchema == null ? Map.of("type", "object", "properties", Map.of()) : inputSchema;
        resultMapping = resultMapping == null ? Map.of() : resultMapping;
        options = options == null ? Map.of() : options;
    }
}
