package com.chatchat.mcpserver.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminLoginAuditLogRepository extends JpaRepository<AdminLoginAuditLog, String>, JpaSpecificationExecutor<AdminLoginAuditLog> {
}
