package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.mcp.contract.McpToolBinding;
import com.chatchat.common.mcp.contract.McpToolContract;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;

import java.time.Duration;

public record RegisteredMcpTool(
    McpToolDefinition definition,
    McpToolExecutor executor,
    boolean enabled,
    boolean agentCallable,
    Duration timeout,
    McpToolRuntimeStatus runtimeStatus
) implements McpToolBinding<ToolInput, ToolOutput> {
    @Override public McpToolContract contract() { return definition; }
}
