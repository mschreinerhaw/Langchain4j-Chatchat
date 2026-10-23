package com.chatchat.api.controller.mcp;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ToolPublicationControllerTest {
    @Test void toolDeletionRequiresPublishingPrivilegeAndUsesAuthenticatedScope() throws Exception {
        var client=mock(McpControlPlaneClient.class);var access=mock(ObjectAccessService.class);var identities=mock(EnterpriseAdminService.class);
        var audits=mock(SysAuditLogRepository.class);var request=new MockHttpServletRequest();
        when(access.user(request)).thenReturn("deleter");when(access.tenant(request)).thenReturn("tenant-a");
        when(identities.hasPermission("deleter","mcp:publication:publish")).thenReturn(true);
        when(identities.hasPermission("deleter","system:permission-center:manage")).thenReturn(true);
        var body=new ObjectMapper().readTree("{\"expectedVersions\":{\"release-id\":2}}");
        when(client.request(anyString(),anyString(),any())).thenReturn(new ObjectMapper().readTree("{\"logicalId\":\"tool-id\",\"deletedVersions\":1}"));
        var controller=new ToolPublicationController(client,access,identities,mock(WorkspaceService.class),audits,mock(SysRoleRepository.class));
        controller.delete(request,"release-id",body);verify(client).request("DELETE","/api/v1/tool-publications/release-id?tenantId=tenant-a&actorId=deleter",body);
        verify(audits).saveAndFlush(argThat(log->"delete-tool".equals(log.getActionName())&&"tool-id".equals(log.getResourceId())&&"deleter".equals(log.getActorId())));
        when(identities.hasPermission("deleter","mcp:publication:publish")).thenReturn(false);
        assertThatThrownBy(()->controller.delete(request,"release-id",body)).isInstanceOf(SecurityException.class);verifyNoMoreInteractions(client);
    }
    @Test void draftUpdateBindsTheAuthenticatedTenantAndActorAndRequiresEditPermission() throws Exception {
        var client=mock(McpControlPlaneClient.class);var access=mock(ObjectAccessService.class);
        var identities=mock(EnterpriseAdminService.class);var request=new MockHttpServletRequest();
        when(access.user(request)).thenReturn("editor");when(access.tenant(request)).thenReturn("tenant-a");
        when(identities.hasPermission("editor","mcp:publication:edit")).thenReturn(true);
        when(identities.hasPermission("editor","system:permission-center:manage")).thenReturn(true);
        var body=new ObjectMapper().readTree("{\"title\":\"Updated\",\"expectedVersion\":0}");
        when(client.request(anyString(),anyString(),any())).thenReturn(body);
        var controller=new ToolPublicationController(client,access,identities,mock(WorkspaceService.class),mock(SysAuditLogRepository.class),mock(SysRoleRepository.class));
        controller.update(request,"release-id",body);
        verify(client).request("PUT","/api/v1/tool-publications/release-id?tenantId=tenant-a&actorId=editor",body);
        when(identities.hasPermission("editor","mcp:publication:edit")).thenReturn(false);
        assertThatThrownBy(()->controller.update(request,"release-id",body)).isInstanceOf(SecurityException.class);
        verifyNoMoreInteractions(client);
    }
    @Test void callerCannotSupplyTenantOrActorThroughTheDraftPayload() throws Exception {
        var client=mock(McpControlPlaneClient.class);var access=mock(ObjectAccessService.class);
        var workspaces=mock(WorkspaceService.class);var identities=mock(EnterpriseAdminService.class);
        var audits=mock(SysAuditLogRepository.class);var roles=mock(SysRoleRepository.class);
        var request=new MockHttpServletRequest();when(access.user(request)).thenReturn("authenticated-user");when(access.tenant(request)).thenReturn("tenant-a");
        when(identities.hasPermission("authenticated-user","mcp:publication:edit")).thenReturn(true);
        when(identities.hasPermission("authenticated-user","system:permission-center:manage")).thenReturn(true);
        var json=new ObjectMapper();var payload=json.readTree("{\"tenantId\":\"other\",\"actorId\":\"admin\"}");
        when(client.request(anyString(),anyString(),any())).thenReturn(json.readTree("{\"id\":\"release\"}"));
        new ToolPublicationController(client,access,identities,workspaces,audits,roles).draft(request,payload);
        verify(client).request(eq("POST"),eq("/api/v1/tool-publications?tenantId=tenant-a&actorId=authenticated-user"),same(payload));
    }
    @Test void backendEnforcesPublishingPrivilegeIndependentlyOfPageVisibility() {
        var client=mock(McpControlPlaneClient.class);var access=mock(ObjectAccessService.class);var identities=mock(EnterpriseAdminService.class);
        var request=new MockHttpServletRequest();when(access.user(request)).thenReturn("user");
        var controller=new ToolPublicationController(client,access,identities,mock(WorkspaceService.class),mock(SysAuditLogRepository.class),mock(SysRoleRepository.class));
        assertThatThrownBy(()->controller.action(request,"release","publish",null)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(client);verify(identities).hasPermission("user","mcp:publication:publish");
    }
}
