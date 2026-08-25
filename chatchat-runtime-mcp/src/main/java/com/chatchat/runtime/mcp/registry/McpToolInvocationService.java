package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import org.springframework.stereotype.Service;

@Service
public class McpToolInvocationService {
    private final McpToolRegistry registry;

    public McpToolInvocationService(McpToolRegistry registry) {
        this.registry = registry;
    }

    public ToolOutput invoke(String toolName, ToolInput input) {
        RegisteredMcpTool tool = registry.require(toolName);
        if (!registry.isActive(tool)) return ToolOutput.failure("MCP tool or capability is disabled: " + toolName);
        return McpKernelBridge.invoke(toolName, tool.executor(), input);
    }
}
