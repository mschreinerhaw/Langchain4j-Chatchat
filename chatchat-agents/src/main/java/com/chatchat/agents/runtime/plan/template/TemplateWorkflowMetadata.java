package com.chatchat.agents.runtime.plan.template;

import com.chatchat.common.mcp.capability.McpTemplateSelectionScope;
import com.chatchat.common.tool.ToolMetadata;
import java.util.*;

/** One view of publisher metadata for direct descriptors and registered MCP envelopes. */
public final class TemplateWorkflowMetadata {
    private TemplateWorkflowMetadata() {}

    public static Map<String,Object> publisher(ToolMetadata tool) {
        if (tool == null || tool.getMetadata() == null) return Map.of();
        Map<String,Object> result=new LinkedHashMap<>();
        Object nested=tool.getMetadata().get("mcpToolMeta");
        if(nested instanceof Map<?,?> map) map.forEach((k,v)->{if(k!=null)result.put(k.toString(),v);});
        result.putAll(tool.getMetadata());
        return Collections.unmodifiableMap(result);
    }

    public static Optional<McpTemplateSelectionScope> fixedScope(ToolMetadata tool) {
        return McpTemplateSelectionScope.fromToolMetadata(publisher(tool))
            .filter(McpTemplateSelectionScope::fixedBindingAuthority);
    }

    public static String assetType(ToolMetadata tool) {
        Map<String,Object> meta=publisher(tool);
        for(String key:List.of("assetType","asset_type")) {
            Object value=meta.get(key);
            if(value!=null && !value.toString().isBlank()) return value.toString().trim().toLowerCase(Locale.ROOT);
        }
        return fixedScope(tool).map(McpTemplateSelectionScope::assetType)
            .map(value->value.trim().toLowerCase(Locale.ROOT)).orElse(null);
    }

    public static boolean assetStages(ToolMetadata tool) {
        if (tool == null) return false;
        if (tool.getParameters() != null && tool.getParameters().stream().anyMatch(parameter ->
            parameter != null && "stage".equals(parameter.getName()) && parameter.getEnumValues() != null
                && Arrays.asList(parameter.getEnumValues()).containsAll(List.of("asset", "template")))) return true;
        Object schema = publisher(tool).get("inputSchema");
        if (!(schema instanceof Map<?,?> input) || !(input.get("properties") instanceof Map<?,?> properties)
            || !(properties.get("stage") instanceof Map<?,?> stage)) return false;
        return stage.get("enum") instanceof Collection<?> values && values.containsAll(List.of("asset", "template"));
    }
}
