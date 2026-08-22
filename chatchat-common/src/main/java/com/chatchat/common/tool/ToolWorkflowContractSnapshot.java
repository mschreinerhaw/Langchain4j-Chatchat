package com.chatchat.common.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Immutable ACTIVE contract loaded from the persistent tool catalog. */
public record ToolWorkflowContractSnapshot(
    String toolId,
    long version,
    String schemaVersion,
    ToolWorkflowRole workflowRole,
    String protocolFamily,
    String inputEnvelope,
    String checksum,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    Map<String, Object> extensions
) {
    public ToolWorkflowContractSnapshot {
        inputSchema = immutable(inputSchema);
        outputSchema = immutable(outputSchema);
        extensions = immutable(extensions);
    }

    public Map<String, Object> asMetadata() {
        Map<String, Object> value = new LinkedHashMap<>(extensions);
        value.put("schemaVersion", schemaVersion);
        value.put("workflowRole", workflowRole == null ? null : workflowRole.name());
        value.put("protocolFamily", protocolFamily);
        value.put("inputEnvelope", inputEnvelope);
        value.put("contractVersion", version);
        value.put("checksum", checksum);
        value.values().removeIf(java.util.Objects::isNull);
        return Map.copyOf(value);
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
