package com.chatchat.agents.runtime;

import java.util.Optional;

/**
 * Stores user-level MCP confirmation preferences outside the runtime loop.
 */
public interface ToolRuntimeUserPolicyStore {

    /**
     * Finds the action.
     *
     * @param tenantId the tenant id value
     * @param userId the user id value
     * @param toolName the tool name value
     * @return the matching action
     */
    Optional<ToolRuntimeAction> findAction(String tenantId, String userId, String toolName);

    /**
     * Saves the action.
     *
     * @param tenantId the tenant id value
     * @param userId the user id value
     * @param toolName the tool name value
     * @param action the action value
     */
    void saveAction(String tenantId, String userId, String toolName, ToolRuntimeAction action);
}
