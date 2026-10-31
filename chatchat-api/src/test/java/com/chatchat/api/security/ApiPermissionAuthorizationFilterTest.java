package com.chatchat.api.security;

import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiPermissionAuthorizationFilterTest {
    @Test void chatReaderMayLoadAnOwnedReportWhileArtifactMutationStillRequiresItsOwnPermission() throws Exception {
        var policy=new SysPermission(); policy.setPermissionCode("system:api:all"); policy.setResourcePath("/api/v1/**");
        policy.setHttpMethod("*"); policy.setStatus("enabled");
        when(adminService.listPermissions()).thenReturn(List.of(policy));
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("workspace:chat:interact")));
        for (String suffix : List.of("", "/resources/answer")) {
            var request=new MockHttpServletRequest("GET","/api/v1/ui-artifacts/ui_owned"+suffix);
            request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"user-1");
            var response=new MockHttpServletResponse(); filter.doFilterInternal(request,response,chain);
            verify(chain).doFilter(request,response);
        }
        var delete=new MockHttpServletRequest("DELETE","/api/v1/ui-artifacts/ui_owned");
        delete.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"user-1");
        var denied=new MockHttpServletResponse(); filter.doFilterInternal(delete,denied,chain);
        assertThat(denied.getStatus()).isEqualTo(403); verify(chain,never()).doFilter(delete,denied);
        assertThat(ApiPermissionAuthorizationFilter.callerCatalogPermissions("GET","/api/v1/ui-artifacts")).isEmpty();
    }
    @Test void chatTaskRoutesExcludeTenantWideMonitoringAndAdministrativeActions() {
        String root = "/api/v1/agent/tasks", id = "00000000-0000-0000-0000-000000000001";
        assertThat(ApiPermissionAuthorizationFilter.callerTaskRoute("POST", root)).isTrue();
        assertThat(ApiPermissionAuthorizationFilter.callerTaskRoute("GET", root + "/runtime/todos")).isTrue();
        for (String suffix : List.of("", "/events", "/events/stream", "/result", "/plan", "/plan-dag", "/plan/versions"))
            assertThat(ApiPermissionAuthorizationFilter.callerTaskRoute("GET", root + "/" + id + suffix)).isTrue();
        for (String suffix : List.of("", "/page", "/runtime", "/runtime/tool-audits", "/runtime/effects", "/runtime/schedules"))
            assertThat(ApiPermissionAuthorizationFilter.callerTaskRoute("GET", root + suffix)).isFalse();
        assertThat(ApiPermissionAuthorizationFilter.callerTaskRoute("POST", root + "/runtime/tasks/" + id + "/kill")).isFalse();
        assertThat(ApiPermissionAuthorizationFilter.callerTaskRoute("DELETE", root + "/" + id)).isFalse();
    }
    @Test void libraryReaderCanReadGrantedSkillsButCannotMutateTheCallerCatalog() throws Exception {
        var permission = new SysPermission(); permission.setPermissionCode("workspace:chat:interact");
        permission.setResourcePath("/api/v1/interactions/**"); permission.setStatus("enabled"); permission.setHttpMethod("*");
        when(adminService.listPermissions()).thenReturn(List.of(permission));
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("capability:library")));
        var request = new MockHttpServletRequest("GET", "/api/v1/interactions/resources/skills");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-1");
        var response = new MockHttpServletResponse(); filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
        var mutation = new MockHttpServletRequest("POST", "/api/v1/interactions/resources/skills");
        mutation.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-1");
        var denied = new MockHttpServletResponse(); filter.doFilterInternal(mutation, denied, chain);
        assertThat(denied.getStatus()).isEqualTo(403); verify(chain, never()).doFilter(mutation, denied);
    }
    @Test void callerCatalogAlternativesDoNotAuthorizeMutationsOrNestedManagementRoutes() {
        assertThat(ApiPermissionAuthorizationFilter.callerCatalogPermissions("GET", "/api/v1/ui-display/trend-semantics")).contains("workspace:chat:interact");
        assertThat(ApiPermissionAuthorizationFilter.callerCatalogPermissions("PUT", "/api/v1/ui-display/trend-semantics")).isEmpty();
        assertThat(ApiPermissionAuthorizationFilter.callerCatalogPermissions("GET", "/api/v1/interactions/resources/skills")).contains("capability:library");
        assertThat(ApiPermissionAuthorizationFilter.callerCatalogPermissions("GET", "/api/v1/interactions/resources/agents")).contains("platform:schedules:manage");
        assertThat(ApiPermissionAuthorizationFilter.callerCatalogPermissions("POST", "/api/v1/interactions/resources/agents")).isEmpty();
        assertThat(ApiPermissionAuthorizationFilter.callerCatalogPermissions("GET", "/api/v1/interactions/resources/agents/private/releases")).isEmpty();
    }
    @Test void categoryMaintenanceReusesPublicationPermissions() {
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("GET","/api/v1/tool-categories")).isEqualTo("mcp:publication:read");
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("DELETE","/api/v1/tool-categories/id")).isEqualTo("mcp:publication:edit");
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("PUT","/api/v1/tool-categories/assignments/id")).isEqualTo("mcp:publication:edit");
    }
    @Test void deletingAReleasedToolUsesPublishingPrivilegeWithoutExposingOtherDeleteRoutes() {
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("DELETE","/api/v1/tool-publications/release-id")).isEqualTo("mcp:publication:publish");
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("DELETE","/api/v1/tool-publications/release-id/publish")).isNull();
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("DELETE","/api/v1/tool-publications/capabilities")).isNull();
    }
    @org.junit.jupiter.api.Test void draftUpdatesReuseOnlyTheExistingEditPermission() {
        org.assertj.core.api.Assertions.assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("PUT", "/api/v1/tool-publications/release-id"))
            .isEqualTo("mcp:publication:edit");
        org.assertj.core.api.Assertions.assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("PUT", "/api/v1/tool-publications/release-id/publish")).isNull();
        org.assertj.core.api.Assertions.assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("PUT", "/api/v1/tool-publications/capabilities")).isNull();
    }
    @Test void templateRoutesMapToExistingSpecificMaintainedPrivileges() {
        String base = "/api/v1/tool-publications/capabilities";
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("PUT", base + "/agents/a/tools/t" )).isEqualTo("platform:agents:workshop");
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("DELETE", base + "/agents/a/tools/t")).isEqualTo("platform:agents:workshop");
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("GET", base + "/permission-catalog")).isEqualTo("system:resource-grants:manage");
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("POST", base + "/publications")).isEqualTo("mcp:publication:publish");
        assertThat(ApiPermissionAuthorizationFilter.templateCapabilityPermission("POST", "/api/v1/tool-publications")).isNull();
    }
    @Test void existingRoleManagerMayReadConsolidatedCatalog() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/v1/permission-center");request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"user-1");
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("system:role:authorize")));
        var response=new MockHttpServletResponse();filter.doFilterInternal(request,response,chain);verify(chain).doFilter(request,response);
    }
    @Test void consolidatedReadAccessDoesNotGrantToolMutation() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/v1/permission-center/tool-grants");request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"user-1");
        var permission=new SysPermission();permission.setPermissionCode("system:permission-center:manage");permission.setResourcePath("/api/v1/permission-center/**");permission.setHttpMethod("*");permission.setStatus("enabled");
        when(adminService.listPermissions()).thenReturn(List.of(permission));when(adminService.getUserView("user-1")).thenReturn(user(List.of("system:role:authorize")));
        var response=new MockHttpServletResponse();filter.doFilterInternal(request,response,chain);assertThat(response.getStatus()).isEqualTo(403);verify(chain,never()).doFilter(request,response);
    }

    @Test
    void chatAttachmentsUseChatPermissionWithoutGrantingDataScienceApis() throws Exception {
        var permission=new SysPermission();permission.setPermissionCode("workspace:chat:interact");
        permission.setResourcePath("/api/v1/interactions/**");permission.setHttpMethod("*");permission.setStatus("enabled");
        var request=new MockHttpServletRequest("POST","/api/v1/interactions/user-assets/analyses");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"user-1");
        when(adminService.listPermissions()).thenReturn(List.of(permission,dataSciencePermission()));
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("workspace:chat:interact")));
        var response=new MockHttpServletResponse();filter.doFilterInternal(request,response,chain);
        verify(chain).doFilter(request,response);
    }

    @Mock private EnterpriseAdminService adminService;
    @Mock private FilterChain chain;

    private ApiPermissionAuthorizationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiPermissionAuthorizationFilter(adminService, new ObjectMapper());
    }

    @Test
    void deniesDataScienceApiWithoutTheDedicatedPermission() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(adminService.listPermissions()).thenReturn(List.of(dataSciencePermission()));
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("capability:library")));

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowsDataScienceApiWithTheDedicatedPermission() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(adminService.listPermissions()).thenReturn(List.of(dataSciencePermission()));
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("capability:data-science")));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void deniesAuthenticatedApiWhenNoDatabasePolicyMatches() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(adminService.listPermissions()).thenReturn(List.of());

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("no enabled database permission policy");
        verify(chain, never()).doFilter(request, response);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/v1/data-science/python/workbench");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-1");
        return request;
    }

    private SysPermission dataSciencePermission() {
        SysPermission permission = new SysPermission();
        permission.setPermissionCode("capability:data-science");
        permission.setPermissionType("menu");
        permission.setResourcePath("/api/v1/data-science/python/**");
        permission.setHttpMethod("*");
        permission.setStatus("enabled");
        return permission;
    }

    private EnterpriseAdminService.UserView user(List<String> permissionCodes) {
        return new EnterpriseAdminService.UserView(
            "user-1", "tenant-1", 100001L, "Tenant", null, "alice", "Alice", null, null,
            "enabled", null, List.of("role-1"), permissionCodes, Instant.now(), Instant.now(), false
        );
    }
}
