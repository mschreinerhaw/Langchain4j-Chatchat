package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.McpToolPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpToolPermissionRepository extends JpaRepository<McpToolPermission, String> {
    /**
     * Finds the by tenant id order by updated at desc.
     *
     * @param tenantId the tenant id value
     * @return the matching by tenant id order by updated at desc
     */
    List<McpToolPermission> findByTenantIdOrderByUpdatedAtDesc(String tenantId);

    /**
     * Finds the by target type and target id and enabled true.
     *
     * @param targetType the target type value
     * @param targetId the target id value
     * @return the matching by target type and target id and enabled true
     */
    List<McpToolPermission> findByTargetTypeAndTargetIdAndEnabledTrue(String targetType, String targetId);

    /**
     * Finds the by tenant id and target type and target id and enabled true order by updated at desc.
     *
     * @param tenantId the tenant id value
     * @param targetType the target type value
     * @param targetId the target id value
     * @return the matching by tenant id and target type and target id and enabled true order by updated at desc
     */
    List<McpToolPermission> findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
        String tenantId,
        String targetType,
        String targetId
    );
}
