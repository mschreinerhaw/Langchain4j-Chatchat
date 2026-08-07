package com.chatchat.mcpserver.authorization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpSynchronizedRoleRepository extends JpaRepository<McpSynchronizedRole, String> {

    List<McpSynchronizedRole> findAllByOrderByRoleNameAscRoleCodeAsc();

    List<McpSynchronizedRole> findByTenantIdOrderByRoleNameAscRoleCodeAsc(String tenantId);

    Optional<McpSynchronizedRole> findFirstByTenantIdAndRoleCodeIgnoreCase(String tenantId, String roleCode);

    Optional<McpSynchronizedRole> findFirstByTenantIdAndRoleNameIgnoreCase(String tenantId, String roleName);
}
