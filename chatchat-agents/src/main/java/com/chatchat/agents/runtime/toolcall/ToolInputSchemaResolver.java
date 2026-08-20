package com.chatchat.agents.runtime.toolcall;

import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves the canonical input contract published for a runtime tool. */
public final class ToolInputSchemaResolver {

    public Map<String, Object> resolve(ToolMetadata metadata) {
        Map<String, Object> published = resolvePublished(metadata);
        if (!published.isEmpty()) {
            return published;
        }
        if (metadata == null) {
            return Map.of();
        }
        return fromParameters(metadata.getParameters());
    }

    public Map<String, Object> resolvePublished(ToolMetadata metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Map<String, Object> published = objectMap(
            metadata.getMetadata() == null ? null : metadata.getMetadata().get("inputSchema"));
        return published.get("properties") instanceof Map<?, ?> ? published : Map.of();
    }

    private Map<String, Object> fromParameters(List<ToolParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", parameter.getType() == null ? "string" : parameter.getType());
            if (parameter.getDefaultValue() != null) {
                property.put("default", parameter.getDefaultValue());
            }
            if (parameter.getEnumValues() != null && parameter.getEnumValues().length > 0) {
                property.put("enum", List.of(parameter.getEnumValues()));
            }
            if (parameter.getMetadata() != null) {
                copyIfPresent(parameter.getMetadata(), property, "format", "aliases", "acceptedSources");
            }
            properties.put(parameter.getName(), property);
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }
        if (properties.isEmpty()) {
            return Map.of();
        }
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", false
        );
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : Map.of();
    }
}
