package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysRoleRepository extends JpaRepository<SysRole, String> {
    List<SysRole> findByTenantIdOrderByRoleNameAsc(String tenantId);

    Optional<SysRole> findByTenantIdAndRoleCode(String tenantId, String roleCode);
}
