package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.mcp.contract.McpToolCatalog;

import java.util.Collection;
import java.util.Optional;

public interface McpToolProvider extends McpToolCatalog {

    Collection<McpToolDefinition> definitions();

    @Override
    default Collection<McpToolDefinition> contracts() {
        return definitions();
    }

    Optional<McpToolExecutor> findExecutor(String toolName);
}
