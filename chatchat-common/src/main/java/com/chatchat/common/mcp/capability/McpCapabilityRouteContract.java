package com.chatchat.common.mcp.capability;

import java.util.Map;

/** Runtime OS interface implemented by MCP dynamic capability routing protocols. */
public interface McpCapabilityRouteContract {
    String contractVersion();
    String parentToolName();
    String implementationIdentityArgument();
    String routingMode();
    Map<String, Object> attributes();
    Map<String, Object> toMetadata();
}
