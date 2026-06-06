package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.SysAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysAuditLogRepository extends JpaRepository<SysAuditLog, String> {
    List<SysAuditLog> findTop100ByOrderByCreatedAtDesc();

    List<SysAuditLog> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
