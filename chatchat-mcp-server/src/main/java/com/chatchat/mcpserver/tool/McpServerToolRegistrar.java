package com.chatchat.mcpserver.tool;

import com.chatchat.agents.tool.ToolRegistry;

/**
 * Registers MCP-server-local tools before the MCP tool list is built.
 */
public interface McpServerToolRegistrar {

    /**
     * Registers tools into the shared tool registry.
     *
     * @param toolRegistry the tool registry value
     */
    void registerTools(ToolRegistry toolRegistry);
}
