package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.McpToolPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpToolPermissionRepository extends JpaRepository<McpToolPermission, String> {
    List<McpToolPermission> findByTenantIdOrderByUpdatedAtDesc(String tenantId);

    List<McpToolPermission> findByTargetTypeAndTargetIdAndEnabledTrue(String targetType, String targetId);

    List<McpToolPermission> findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
        String tenantId,
        String targetType,
        String targetId
    );
}
