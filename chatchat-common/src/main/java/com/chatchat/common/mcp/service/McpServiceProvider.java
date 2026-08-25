package com.chatchat.common.mcp.service;

import java.util.Collection;

/** SPI implemented by remote, local or plugin MCP service families. */
public interface McpServiceProvider {
    String providerId();
    Collection<McpServiceDescriptor> services();
    Collection<McpToolDescriptor> tools(McpToolQuery query);
    boolean supports(String serviceId, String toolName);
    McpServiceResult invoke(McpServiceCall call);
    default void refresh() { }
}
