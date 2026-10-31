package com.chatchat.enterprise.service;

import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.entity.mcp.McpToolPermission;
import com.chatchat.enterprise.repository.identity.SysPermissionRepository;
import com.chatchat.enterprise.repository.mcp.McpToolPermissionRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class McpRoleGrantMigrationTest {
    @Test void doesNotRestoreRevokedCompanionsAfterMigrationHasCompleted() {
        var permissions = mock(SysPermissionRepository.class);
        var tools = mock(McpToolPermissionRepository.class);
        var identities = mock(EnterpriseAdminService.class);
        when(permissions.findByPermissionCode(McpRoleGrantMigration.MARKER)).thenReturn(Optional.of(new SysPermission()));
        new McpRoleGrantMigration(permissions, tools, identities).migrate();
        verifyNoInteractions(tools, identities);
        verify(permissions, never()).save(any());
    }
    @Test void repairsExistingToolRulesOnceAndPersistsAMarkerInTheSameTransaction() {
        var permissions = mock(SysPermissionRepository.class);
        var tools = mock(McpToolPermissionRepository.class);
        var identities = mock(EnterpriseAdminService.class);
        var tool = new McpToolPermission();
        when(permissions.findByPermissionCode(McpRoleGrantMigration.MARKER)).thenReturn(Optional.empty());
        when(tools.findAll()).thenReturn(List.of(tool));
        new McpRoleGrantMigration(permissions, tools, identities).migrate();
        var order = inOrder(identities, permissions);
        order.verify(identities).synchronizeToolResourceGrant(tool);
        order.verify(permissions).save(argThat(p -> McpRoleGrantMigration.MARKER.equals(p.getPermissionCode()) && "internal".equals(p.getPermissionType())));
    }
}
