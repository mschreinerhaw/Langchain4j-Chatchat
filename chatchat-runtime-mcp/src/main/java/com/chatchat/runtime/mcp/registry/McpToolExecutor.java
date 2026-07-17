package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;

@FunctionalInterface
public interface McpToolExecutor {
    ToolOutput execute(ToolInput input);

    default McpToolRuntimeStatus runtimeStatus() {
        return McpToolRuntimeStatus.AVAILABLE;
    }
}
