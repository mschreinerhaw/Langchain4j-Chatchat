package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysAuditLogRepository extends JpaRepository<SysAuditLog, String> {
    List<SysAuditLog> findTop100ByOrderByCreatedAtDesc();

    List<SysAuditLog> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<SysAuditLog> findTop100ByModuleNameOrderByCreatedAtDesc(String moduleName);

    List<SysAuditLog> findTop100ByTenantIdAndModuleNameOrderByCreatedAtDesc(String tenantId, String moduleName);
}
