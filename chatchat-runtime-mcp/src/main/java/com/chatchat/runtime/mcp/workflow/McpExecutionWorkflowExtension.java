package com.chatchat.runtime.mcp.workflow;

import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;

/**
 * Optional extension point around MCP provider execution.
 *
 * <p>Protocol transports and tool families remain {@code McpServiceProvider}
 * implementations. This SPI is for cross-cutting execution behavior such as
 * policy enrichment, tracing, result normalization, and future governance.</p>
 */
public interface McpExecutionWorkflowExtension {

    default int order() { return 0; }

    default boolean supports(McpExecutionWorkflow.Context context) { return true; }

    default McpServiceCall beforeInvoke(McpExecutionWorkflow.Context context,
                                        McpServiceCall call) {
        return call;
    }

    default McpServiceResult afterInvoke(McpExecutionWorkflow.Context context,
                                         McpServiceCall call,
                                         McpServiceResult result) {
        return result;
    }
}
