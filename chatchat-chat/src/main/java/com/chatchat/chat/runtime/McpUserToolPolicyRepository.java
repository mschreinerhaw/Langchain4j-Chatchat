package com.chatchat.chat.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpUserToolPolicyRepository extends JpaRepository<McpUserToolPolicyEntity, String> {

    /**
     * Finds the by tenant id and user id and tool name.
     *
     * @param tenantId the tenant id value
     * @param userId the user id value
     * @param toolName the tool name value
     * @return the matching by tenant id and user id and tool name
     */
    Optional<McpUserToolPolicyEntity> findByTenantIdAndUserIdAndToolName(String tenantId, String userId, String toolName);
}
