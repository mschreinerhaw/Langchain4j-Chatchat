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

    @Test void templateAgentScopeUsesCurrentPrincipalAndActualPublishedBindingPolicy() {
        var identities=mock(EnterpriseAdminService.class);
        var principal=mock(EnterpriseAdminService.UserView.class);
        when(principal.tenantId()).thenReturn("tenant");when(principal.status()).thenReturn("enabled");
        when(identities.getUserView("author")).thenReturn(principal);
        var controller=new EnterpriseMcpAuthorizationSyncController(identities,mock(SysRoleRepository.class),
            mock(SysTenantRepository.class),mock(McpToolAssetRepository.class),mock(McpToolPermissionRepository.class),mock(InternalCredentialProperties.class));
        var agents=mock(com.chatchat.chat.skills.catalog.SkillCatalogService.class);
        var policy=mock(com.chatchat.api.service.AgentTemplateToolPolicy.class);
        ReflectionTestUtils.setField(controller,"agents",agents);ReflectionTestUtils.setField(controller,"agentTools",policy);
        var bound=mock(com.chatchat.chat.skills.model.SkillDefinition.class);when(bound.id()).thenReturn("bound");
        when(bound.label()).thenReturn("Bound agent");when(bound.marketStatus()).thenReturn("published");
        var unbound=mock(com.chatchat.chat.skills.model.SkillDefinition.class);when(unbound.id()).thenReturn("unbound");
        when(agents.list()).thenReturn(List.of(bound,unbound));
        String tool="tpl_01234567890123456789012345678901";
        when(policy.allowed("tenant","author","bound",java.util.Set.of(tool))).thenReturn(java.util.Set.of(tool));
        when(policy.allowed("tenant","author","unbound",java.util.Set.of(tool))).thenReturn(java.util.Set.of());
        org.assertj.core.api.Assertions.assertThat(controller.templateBindings("tenant","author",tool).getData())
            .singleElement().satisfies(value->org.assertj.core.api.Assertions.assertThat(value).containsEntry("agentId","bound"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.templateBindings("other","author",tool))
            .isInstanceOf(SecurityException.class);
    }

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
