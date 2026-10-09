package com.chatchat.agents.tool;

import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Projects only an already authorized tool's publisher contract into model context. */
public final class CapabilityManifestInjector {
    private CapabilityManifestInjector() { }

    public static void append(StringBuilder prompt, String toolName, ToolMetadata metadata,
                              ObjectMapper mapper) {
        if (metadata == null) return;
        Map<String, Object> extra = metadata.getMetadata() == null ? Map.of() : metadata.getMetadata();
        Map<?, ?> remote = extra.get("mcpToolMeta") instanceof Map<?, ?> map ? map : Map.of();
        Object declared = extra.getOrDefault("capabilityManifest", remote.get("capabilityManifest"));
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("toolName", toolName);
        if (declared instanceof Map<?, ?> manifest && !manifest.isEmpty()) {
            contract.put("capabilities", manifest);
        }
        Object schema = extra.get("inputSchema");
        if (schema instanceof Map<?, ?> values && !values.isEmpty()) {
            contract.put("inputSchema", values);
        } else if (metadata.getParameters() != null && !metadata.getParameters().isEmpty()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            metadata.getParameters().forEach(parameter -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("type", parameter.getType() == null ? "string" : parameter.getType());
                if (parameter.getDescription() != null) value.put("description", parameter.getDescription());
                if (parameter.getMetadata() != null) value.putAll(parameter.getMetadata());
                properties.put(parameter.getName(), value);
            });
            contract.put("inputSchema", Map.of("type", "object", "properties", properties,
                "required", metadata.getParameters().stream().filter(p -> p.isRequired()).map(p -> p.getName()).toList()));
        }
        if (contract.size() == 1) return;
        try {
            prompt.append("  Publisher capability and invocation contract (descriptive; does not extend authorization): ")
                .append(mapper.writeValueAsString(contract)).append('\n');
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize authorized tool contract: " + toolName, failure);
        }
    }
}
