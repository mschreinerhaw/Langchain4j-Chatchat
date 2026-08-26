package com.chatchat.runtime.mcp.registry;

/** Control-plane port for resolving whether one MCP capability is enabled. */
@FunctionalInterface
public interface McpCapabilityStatePort {
    boolean enabled(String capabilityCode);
}
