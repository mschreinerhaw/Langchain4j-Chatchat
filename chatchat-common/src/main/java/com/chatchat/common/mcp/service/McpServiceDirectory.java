package com.chatchat.common.mcp.service;

import java.util.List;
import java.util.Optional;

/** API-facing dynamic directory for every MCP service, tool, invocation and repair strategy. */
public interface McpServiceDirectory {
    List<McpServiceDescriptor> services();
    List<McpToolDescriptor> tools(McpToolQuery query);
    default Optional<McpToolDescriptor> findTool(String serviceId, String toolName) {
        return tools(new McpToolQuery(serviceId, null, java.util.Set.of(toolName))).stream().findFirst();
    }
    McpServiceResult invoke(McpServiceCall call);
    McpResultRepairResult repair(McpResultRepairRequest request);
    void refresh();
}
