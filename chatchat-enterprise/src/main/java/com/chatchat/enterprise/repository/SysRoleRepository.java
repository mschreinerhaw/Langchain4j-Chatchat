package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysRoleRepository extends JpaRepository<SysRole, String> {
    /**
     * Finds the by tenant id order by role name asc.
     *
     * @param tenantId the tenant id value
     * @return the matching by tenant id order by role name asc
     */
    List<SysRole> findByTenantIdOrderByRoleNameAsc(String tenantId);

    /**
     * Finds the by tenant id and role code.
     *
     * @param tenantId the tenant id value
     * @param roleCode the role code value
     * @return the matching by tenant id and role code
     */
    Optional<SysRole> findByTenantIdAndRoleCode(String tenantId, String roleCode);
}
