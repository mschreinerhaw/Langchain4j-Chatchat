package com.chatchat.runtime.mcp.registry;

import java.time.Duration;

public record RegisteredMcpTool(
    McpToolDefinition definition,
    McpToolExecutor executor,
    boolean enabled,
    boolean agentCallable,
    Duration timeout,
    McpToolRuntimeStatus runtimeStatus
) { }
