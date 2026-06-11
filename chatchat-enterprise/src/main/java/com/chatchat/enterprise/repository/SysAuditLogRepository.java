package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysAuditLogRepository extends JpaRepository<SysAuditLog, String> {
    /**
     * Finds the top100 by order by created at desc.
     *
     * @return the matching top100 by order by created at desc
     */
    List<SysAuditLog> findTop100ByOrderByCreatedAtDesc();

    /**
     * Finds the top100 by tenant id order by created at desc.
     *
     * @param tenantId the tenant id value
     * @return the matching top100 by tenant id order by created at desc
     */
    List<SysAuditLog> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);

    /**
     * Finds the top100 by module name order by created at desc.
     *
     * @param moduleName the module name value
     * @return the matching top100 by module name order by created at desc
     */
    List<SysAuditLog> findTop100ByModuleNameOrderByCreatedAtDesc(String moduleName);

    /**
     * Finds the top100 by tenant id and module name order by created at desc.
     *
     * @param tenantId the tenant id value
     * @param moduleName the module name value
     * @return the matching top100 by tenant id and module name order by created at desc
     */
    List<SysAuditLog> findTop100ByTenantIdAndModuleNameOrderByCreatedAtDesc(String tenantId, String moduleName);
}
