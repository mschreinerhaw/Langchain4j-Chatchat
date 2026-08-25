package com.chatchat.common.mcp.contract;

import com.chatchat.common.kernel.RuntimeOsKernel;

/** A governed MCP declaration bound to its Kernel executor. */
public interface McpToolBinding<I, O> {
    McpToolContract contract();
    RuntimeOsKernel<I, O> executor();
}
