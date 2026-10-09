package com.chatchat.agents.orchestration.analysis.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** The versioned manifest is shared with frontend adapters. Deployment policy may only narrow it. */
public final class VisualizationCapabilityRegistry {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Map<String, Object>> capabilities;

    public VisualizationCapabilityRegistry(Collection<String> authorizedTypes, Collection<String> renderedTypes) {
        Map<String, Map<String, Object>> active = new LinkedHashMap<>();
        Object raw = resource("visualization-capabilities.json").get("capabilities");
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> definition) {
            String type = String.valueOf(definition.get("type"));
            if (authorizedTypes.contains(type) && renderedTypes.contains(type)) {
                Map<String, Object> value = new LinkedHashMap<>();
                definition.forEach((key, cell) -> value.put(String.valueOf(key), cell));
                active.put(type, Collections.unmodifiableMap(value));
            }
        }
        capabilities = Collections.unmodifiableMap(active);
    }

    public static VisualizationCapabilityRegistry active() {
        var manifest = resource("visualization-capabilities.json");
        List<String> types = ((List<?>) manifest.get("capabilities")).stream()
            .map(value -> String.valueOf(((Map<?, ?>) value).get("type"))).toList();
        String policy = System.getProperty("chatchat.visualization.allowed-types", "*");
        return new VisualizationCapabilityRegistry("*".equals(policy) ? types : Arrays.asList(policy.split(",")), types);
    }

    public boolean supports(String type) { return capabilities.containsKey(type); }
    public static VisualizationCapabilityRegistry active(Map<String, Object> attributes) {
        var active = active();
        if (attributes.get("visualizationAuthorizedTypes") instanceof Collection<?> authorized)
            active = new VisualizationCapabilityRegistry(authorized.stream().map(String::valueOf).toList(), active.types());
        Object advertised = attributes.get("visualizationSupportedTypes");
        if (!(advertised instanceof Collection<?>)) advertised = ReportBlockSchema.map(attributes.get("toolInput")).get("visualizationSupportedTypes");
        if (!(advertised instanceof Collection<?> types)) return active;
        return new VisualizationCapabilityRegistry(active.types(), types.stream().map(String::valueOf).toList());
    }
    public String rendererType(String type) {
        if (!supports(type)) throw new IllegalArgumentException("UNAUTHORIZED_OR_UNREGISTERED_CHART");
        return String.valueOf(capabilities.get(type).get("rendererType"));
    }
    public List<String> types() { return List.copyOf(capabilities.keySet()); }
    public List<Map<String, Object>> describe() { return List.copyOf(capabilities.values()); }
    public Map<String, Object> reportBlockSchema() {
        var schema = resource("report-block.schema.json");
        ReportBlockSchema.map(ReportBlockSchema.map(schema.get("properties")).get("chartType")).put("enum", types());
        return schema;
    }

    static Map<String, Object> resource(String name) {
        try (var stream = VisualizationCapabilityRegistry.class.getResourceAsStream("/runtime/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing visualization contract: " + name);
            return JSON.readValue(stream, new TypeReference<>() {});
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Invalid visualization contract: " + name, failure);
        }
    }
}
