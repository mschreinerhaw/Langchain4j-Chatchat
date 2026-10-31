package com.chatchat.enterprise.service;

import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.repository.identity.SysPermissionRepository;
import com.chatchat.enterprise.repository.mcp.McpToolPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One-time repair of the two persisted MCP authorization layers, preserving invocation scopes. */
@Component
@RequiredArgsConstructor
public class McpRoleGrantMigration {
    static final String MARKER = "system:mcp-role-resource-sync:v1";
    private final SysPermissionRepository permissions;
    private final McpToolPermissionRepository tools;
    private final EnterpriseAdminService identities;

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    @Transactional
    public void migrate() {
        if (permissions.findByPermissionCode(MARKER).isPresent()) return;
        tools.findAll().forEach(identities::synchronizeToolResourceGrant);
        SysPermission marker = new SysPermission();
        marker.setPermissionCode(MARKER); marker.setPermissionName("MCP role resource grant synchronization V1");
        marker.setPermissionType("internal"); permissions.save(marker);
    }
}
