package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.tool.ToolMetadata;

/** Driver port used by the MCP Runtime core to publish tools into a host runtime. */
public interface McpToolPublicationPort {
    void publish(String toolName, ToolMetadata metadata, McpToolExecutor executor);
    void unpublish(String toolName);
}
