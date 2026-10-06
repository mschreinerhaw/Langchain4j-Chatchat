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
    void cachesDiscoveryButExecutionHttpRequestsReadCurrentDatabase() throws Exception {
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

        controller.snapshot(false);
        controller.snapshot(false);

        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/enterprise/mcp-auth/snapshot").param("fresh", "true"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/enterprise/mcp-auth/snapshot").param("fresh", "true"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        verify(adminService, times(3)).listUserViews(null);
        verify(roles, times(3)).findAll();
        verify(tenants, times(3)).findAllByOrderByTenantNameAsc();
        verify(tools, times(3)).findAllByOrderByLocalToolNameAsc();
        verify(permissions, times(3)).findAll();
    }
}
