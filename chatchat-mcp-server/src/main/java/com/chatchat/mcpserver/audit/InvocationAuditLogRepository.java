package com.chatchat.mcpserver.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvocationAuditLogRepository extends JpaRepository<InvocationAuditLog, String> {

    List<InvocationAuditLog> findTop100ByOrderByCreatedAtDesc();
}
