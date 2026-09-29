package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolDataType;
import com.chatchat.common.tool.ToolWorkflowContract;
import java.util.LinkedHashMap;
import java.util.Map;

/** Publication-side defaults from explicit workflow contracts, never from business names. */
final class McpToolPurposeMetadata {
    private McpToolPurposeMetadata() {}

    static Map<String, Object> enrich(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>(source == null ? Map.of() : source);
        String declared = ToolDataType.declared(source);
        if (declared == null) {
            declared = ToolWorkflowContract.declaredDescriptorRole(source).map(role -> switch (role) {
                case ASSET_DISCOVERY -> ToolDataType.ASSET_QUERY;
                case TEMPLATE_DISCOVERY -> ToolDataType.TEMPLATE_QUERY;
                case TEMPLATE_EXECUTION -> ToolDataType.ACTION_EXECUTION;
                case DIRECT -> ToolDataType.UNKNOWN;
            }).orElse(ToolDataType.UNKNOWN).name();
        }
        result.put(ToolDataType.METADATA_KEY, declared);
        return result;
    }
}
