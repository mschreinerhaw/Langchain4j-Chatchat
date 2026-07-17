package com.chatchat.runtime.mcp.registry;

import java.util.Collection;
import java.util.Optional;

public interface McpToolProvider {
    String capabilityCode();

    Collection<McpToolDefinition> definitions();

    Optional<McpToolExecutor> findExecutor(String toolName);
}
