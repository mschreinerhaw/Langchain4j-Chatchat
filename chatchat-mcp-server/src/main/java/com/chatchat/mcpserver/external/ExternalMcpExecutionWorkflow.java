package com.chatchat.mcpserver.external;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;

/** Add another implementation to support a new external MCP transport/workflow. */
public interface ExternalMcpExecutionWorkflow {
    String id();
    List<McpSchema.Tool> discover(ExternalMcpService service);
    McpSchema.CallToolResult invoke(ExternalMcpService service, String toolName, Map<String, Object> arguments);
}
