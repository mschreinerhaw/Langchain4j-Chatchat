package com.chatchat.common.mcp.contract;

import java.util.Collection;

/** Standard discovery port implemented by every module contributing MCP tools. */
public interface McpToolCatalog {
    String capabilityCode();
    Collection<? extends McpToolContract> contracts();
}
