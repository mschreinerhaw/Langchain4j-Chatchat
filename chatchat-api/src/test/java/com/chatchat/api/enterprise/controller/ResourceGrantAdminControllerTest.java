package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ResourceGrantAdminControllerTest {
    @Test
    void savesAgentScopedGrantOnlyForBoundRoleAndFiltersLegacyList() throws Exception {
        ResourceGrantRepository repository = mock(ResourceGrantRepository.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        when(admin.getUserView("user-a")).thenReturn(new EnterpriseAdminService.UserView(
            "user-a", "tenant-a", 100001L, null, "user-a", "User A", null, null,
            "enabled", null, List.of(), List.of(), null, null));
        var role = new com.chatchat.enterprise.entity.identity.SysRole();
        role.setId("role-a"); role.setTenantId("tenant-a");
        when(admin.getRoleAuthorization("role-a")).thenReturn(new EnterpriseAdminService.RoleAuthorizationView(
            role, List.of(), List.of(), List.of(), List.of("agent-a")));
        ResourceGrant grant = new ResourceGrant();
        grant.setTenantId("tenant-a"); grant.setPrincipalType("ROLE"); grant.setPrincipalId("role-a");
        grant.setAgentId("agent-a"); grant.setResourceType("SKILL"); grant.setResourceId("skill-a"); grant.setEffect("ALLOW");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-a");
        var controller = new ResourceGrantAdminController(repository, admin);
        controller.create(request, grant);
        verify(repository).save(grant);
        when(repository.findByTenantIdAndResourceTypeOrderByUpdatedAtDesc("tenant-a", "SKILL"))
            .thenReturn(List.of(grant));
        MockMvc mvc = standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/enterprise/resource-grants").requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
            .param("tenantId", "tenant-a").param("resourceType", "SKILL"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/enterprise/resource-grants").requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
            .param("tenantId", "tenant-a").param("resourceType", "SKILL").param("agentId", "agent-a"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].agentId").value("agent-a"));
        grant.setAgentId("agent-b");
        assertThatThrownBy(() -> controller.create(request, grant)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not bound");
    }

    @Test
    void bindsNamedListParametersWithoutCompilerParameterMetadata() throws Exception {
        ResourceGrantRepository repository = mock(ResourceGrantRepository.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        when(admin.getUserView("user-a")).thenReturn(new EnterpriseAdminService.UserView(
            "user-a", "tenant-a", 100001L, null, "user-a", "User A", null, null,
            "enabled", null, List.of(), List.of(), null, null));
        when(repository.findByTenantIdAndResourceTypeOrderByUpdatedAtDesc("tenant-a", "KNOWLEDGE"))
            .thenReturn(List.of());
        MockMvc mvc = standaloneSetup(new ResourceGrantAdminController(repository, admin)).build();

        mvc.perform(get("/api/v1/enterprise/resource-grants")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
                .param("tenantId", "tenant-a")
                .param("resourceType", "KNOWLEDGE"))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsCrossTenantGrantBeforeWriting() {
        ResourceGrantRepository repository = mock(ResourceGrantRepository.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-a");
        when(admin.getUserView("user-a")).thenReturn(new EnterpriseAdminService.UserView(
            "user-a", "tenant-a", 100001L, null, "user-a", "User A", null, null,
            "enabled", null, List.of(), List.of(), null, null));
        ResourceGrant grant = new ResourceGrant();
        grant.setTenantId("tenant-b");
        grant.setResourceType("SKILL");
        grant.setResourceId("skill-1");
        grant.setPrincipalType("TENANT");
        grant.setPrincipalId("tenant-b");
        grant.setEffect("ALLOW");

        assertThatThrownBy(() -> new ResourceGrantAdminController(repository, admin).create(request, grant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cross-tenant");
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsRoleBoundWorkflowGrant() {
        ResourceGrantRepository repository = mock(ResourceGrantRepository.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-a");
        when(admin.getUserView("user-a")).thenReturn(new EnterpriseAdminService.UserView(
            "user-a", "tenant-a", 100001L, null, "user-a", "User A", null, null,
            "enabled", null, List.of(), List.of(), null, null));
        ResourceGrant grant = new ResourceGrant();
        grant.setTenantId("tenant-a");
        grant.setResourceType("workflow");
        grant.setResourceId("evidence-recovery");
        grant.setPrincipalType("role");
        grant.setPrincipalId("business-admin");
        grant.setEffect("allow");

        new ResourceGrantAdminController(repository, admin).create(request, grant);

        verify(repository).save(grant);
        org.assertj.core.api.Assertions.assertThat(grant.getResourceType()).isEqualTo("WORKFLOW");
        org.assertj.core.api.Assertions.assertThat(grant.getPrincipalType()).isEqualTo("ROLE");
    }
}
