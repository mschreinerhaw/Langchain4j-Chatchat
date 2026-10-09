package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.mcp.contract.McpToolCatalog;

import java.util.Collection;
import java.util.Optional;
import java.util.Map;

public interface McpToolProvider extends McpToolCatalog {

    Collection<McpToolDefinition> definitions();

    @Override
    default Collection<McpToolDefinition> contracts() {
        return definitions();
    }

    Optional<McpToolExecutor> findExecutor(String toolName);

    /** Publisher-owned affordances; never inferred from a user question or tool name. */
    default Map<String, Object> capabilityManifest(String toolName) {
        return Map.of();
    }
}
