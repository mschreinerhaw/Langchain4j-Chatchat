package com.chatchat.agents.runtime.plan.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** API service template workflow. */
public final class ApiTemplateWorkflowPlugin extends ProtocolFamilyTemplateWorkflowPlugin {
    public ApiTemplateWorkflowPlugin() {
        super("api-template-workflow.v1",
            Set.of("mcp.api-template.v1", "mcp.api-template-discovery.v1", "mcp.api-service-asset.v1"),
            Set.of("api_service"));
    }

    @Override
    public Map<String, Object> templateDiscoveryInput(Map<String, Object> assetInput) {
        Map<String, Object> source = assetInput == null ? Map.of() : assetInput;
        Object existingFilters = source.get("filters");
        if (existingFilters instanceof Map<?, ?> filters) {
            Map<String, Object> copied = new LinkedHashMap<>();
            filters.forEach((key, value) -> {
                if (key != null) copied.put(String.valueOf(key), value);
            });
            return Map.of("filters", copied);
        }
        Object queryValue = source.get("query");
        String query = queryValue == null ? "" : String.valueOf(queryValue).trim();
        if (query.isEmpty()) {
            return Map.of("filters", Map.of());
        }
        return Map.of("filters", Map.of("intent", query, "queryTerms", List.of(query)));
    }
}
