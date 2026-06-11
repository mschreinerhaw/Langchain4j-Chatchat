package com.chatchat.agents.runtime;

import java.util.Optional;

/**
 * Stores user-level MCP confirmation preferences outside the runtime loop.
 */
public interface ToolRuntimeUserPolicyStore {

    Optional<ToolRuntimeAction> findAction(String tenantId, String userId, String toolName);

    void saveAction(String tenantId, String userId, String toolName, ToolRuntimeAction action);
}
