package com.chatchat.api.enterprise.controller;

import com.chatchat.agents.runtime.federation.AgentHealthTracker;
import com.chatchat.api.controller.agent.DocumentLibraryReadPort;
import com.chatchat.api.enterprise.LoginAuditService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.domain.DomainSkillService;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.runtime.agent.AgentCardDiscoveryPort;
import com.chatchat.common.runtime.agent.AgentCredentialResolver;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.datasource.DataSourceConfigRepository;
import com.chatchat.enterprise.repository.identity.SysOrgRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.repository.mcp.McpToolAssetRepository;
import com.chatchat.enterprise.repository.mcp.McpToolPermissionRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnterpriseAdminControllerAgentRegistryTest {

    @Mock private EnterpriseAdminService adminService;
    @Mock private SysTenantRepository tenantRepository;
    @Mock private SysOrgRepository orgRepository;
    @Mock private SysRoleRepository roleRepository;
    @Mock private McpToolAssetRepository toolAssetRepository;
    @Mock private McpToolPermissionRepository toolPermissionRepository;
    @Mock private DataSourceConfigRepository dataSourceRepository;
    @Mock private SysAuditLogRepository auditLogRepository;
    @Mock private SkillCatalogService skillCatalogService;
    @Mock private LoginAuditService loginAuditService;
    @Mock private AgentRegistryPort agentRegistry;
    @Mock private AgentCardDiscoveryPort agentCards;
    @Mock private AgentHealthTracker agentHealth;
    @Mock private ObjectProvider<AgentCredentialResolver> agentCredentials;
    @Mock private DocumentLibraryReadPort documentLibrary;
    @Mock private DomainSkillService domainSkillService;
    @Mock private ResourceAuthorizationPort resourceAuthorization;

    @InjectMocks private EnterpriseAdminController controller;

    @Test
    void rejectsRemoteAgentDocumentsOutsideCurrentPermissions() {
        MockHttpServletRequest request = authorizedAdminRequest();
        when(documentLibrary.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertThatThrownBy(() -> controller.registerAgentComputeProvider(request,
            descriptor(Map.of("documentIds", List.of("private-doc"), "skillIds", List.of()))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("knowledge documents outside");

        verify(agentRegistry, never()).register(org.mockito.ArgumentMatchers.any());
    }

    private MockHttpServletRequest authorizedAdminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "admin-1");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-1");
        EnterpriseAdminService.UserView user = new EnterpriseAdminService.UserView(
            "admin-1", "tenant-1", 1L, "Tenant", null, "admin", "Admin", "", "", "ACTIVE",
            null, List.of("role-admin"), List.of(), Instant.now(), Instant.now(), true);
        when(adminService.getUserView("admin-1")).thenReturn(user);
        when(adminService.hasAllAgentAccess(user)).thenReturn(true);
        when(adminService.authorizationRoleKeys("admin-1")).thenReturn(List.of("role-admin"));
        return request;
    }

    private AgentDescriptor descriptor(Map<String, Object> grants) {
        return new AgentDescriptor("group.risk", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.HTTP_JSON, URI.create("https://agent.example/a2a"), Set.of(),
            AgentDescriptor.TrustLevel.GROUP_TRUSTED, AgentDescriptor.DataAccessMode.RUNTIME_MANAGED,
            Set.of(), Set.of(), "v1", "", 50, true, Map.of("analysisGrants", grants));
    }
}
