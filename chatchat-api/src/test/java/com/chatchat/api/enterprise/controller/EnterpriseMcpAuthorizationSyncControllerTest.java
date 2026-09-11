package com.chatchat.api.enterprise.controller;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.repository.mcp.McpToolAssetRepository;
import com.chatchat.enterprise.repository.mcp.McpToolPermissionRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseMcpAuthorizationSyncControllerTest {

    @Test
    void reusesSnapshotWithinConfiguredTtl() {
        EnterpriseAdminService adminService = mock(EnterpriseAdminService.class);
        SysRoleRepository roles = mock(SysRoleRepository.class);
        SysTenantRepository tenants = mock(SysTenantRepository.class);
        McpToolAssetRepository tools = mock(McpToolAssetRepository.class);
        McpToolPermissionRepository permissions = mock(McpToolPermissionRepository.class);
        InternalCredentialProperties credential = mock(InternalCredentialProperties.class);
        when(adminService.listUserViews(null)).thenReturn(List.of());
        when(roles.findAll()).thenReturn(List.of());
        when(tenants.findAllByOrderByTenantNameAsc()).thenReturn(List.of());
        when(tools.findAllByOrderByLocalToolNameAsc()).thenReturn(List.of());
        when(permissions.findAll()).thenReturn(List.of());

        EnterpriseMcpAuthorizationSyncController controller =
            new EnterpriseMcpAuthorizationSyncController(
                adminService, roles, tenants, tools, permissions, credential);
        ReflectionTestUtils.setField(controller, "snapshotCacheTtlMs", 60_000L);

        controller.snapshot();
        controller.snapshot();

        verify(adminService, times(1)).listUserViews(null);
        verify(roles, times(1)).findAll();
        verify(tenants, times(1)).findAllByOrderByTenantNameAsc();
        verify(tools, times(1)).findAllByOrderByLocalToolNameAsc();
        verify(permissions, times(1)).findAll();
    }
}
