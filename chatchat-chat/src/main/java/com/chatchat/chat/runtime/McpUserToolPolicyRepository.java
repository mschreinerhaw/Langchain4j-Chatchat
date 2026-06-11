package com.chatchat.chat.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpUserToolPolicyRepository extends JpaRepository<McpUserToolPolicyEntity, String> {

    Optional<McpUserToolPolicyEntity> findByTenantIdAndUserIdAndToolName(String tenantId, String userId, String toolName);
}
