package com.chatchat.mcpserver.authorization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpSynchronizedRoleRepository extends JpaRepository<McpSynchronizedRole, String> {

    List<McpSynchronizedRole> findAllByOrderByRoleNameAscRoleCodeAsc();

    List<McpSynchronizedRole> findByTenantIdOrderByRoleNameAscRoleCodeAsc(String tenantId);
}
