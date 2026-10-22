package com.chatchat.api.enterprise.controller;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.repository.mcp.*;
import com.chatchat.enterprise.repository.identity.*;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PermissionCenterControllerTest {
    final ObjectAccessService access=mock(ObjectAccessService.class);
    final WorkspaceService workspaces=mock(WorkspaceService.class);
    final EnterpriseAdminService identities=mock(EnterpriseAdminService.class);
    final McpToolPermissionRepository grants=mock(McpToolPermissionRepository.class);
    final McpToolAssetRepository tools=mock(McpToolAssetRepository.class);
    final SysRoleRepository roles=mock(SysRoleRepository.class);
    final SysUserRepository users=mock(SysUserRepository.class);
    final SysAuditLogRepository audits=mock(SysAuditLogRepository.class);
    final ResourceGrantRepository resources=mock(ResourceGrantRepository.class);
    final HttpServletRequest request=mock(HttpServletRequest.class);
    final PermissionCenterController controller=new PermissionCenterController(access,workspaces,identities,grants,tools,roles,users,audits,resources);
    @Test void roleManagerReadsOnlyCurrentTenantProjectionWithoutLosingServiceScopes(){
        when(access.user(request)).thenReturn("operator");when(access.tenant(request)).thenReturn("tenant");when(identities.hasPermission("operator","system:role:authorize")).thenReturn(true);
        var role=new SysRole();role.setId("role");role.setTenantId("tenant");when(roles.findByTenantIdOrderByRoleNameAsc("tenant")).thenReturn(List.of(role));
        when(identities.getRoleAuthorization("role")).thenReturn(new EnterpriseAdminService.RoleAuthorizationView(role,List.of(),List.of(),List.of(),List.of("local-service")));
        var scoped=new ResourceGrant();scoped.setAgentId("local-service");scoped.setResourceType("KNOWLEDGE");scoped.setResourceId("doc");when(resources.findByTenantIdOrderByUpdatedAtDesc("tenant")).thenReturn(List.of(scoped));
        var result=controller.catalog(request).getData();assertThat(result.get("tenantId")).isEqualTo("tenant");assertThat(result.get("resourceGrants")).isEqualTo(List.of(scoped));verify(workspaces).requireAvailable(request,"permissions");verify(resources).findByTenantIdOrderByUpdatedAtDesc("tenant");
    }
    @Test void authenticatedUserWithoutAdministrativeRightsCannotReadPolicyCatalog(){
        when(access.user(request)).thenReturn("reader");assertThatThrownBy(()->controller.catalog(request)).isInstanceOf(SecurityException.class);verifyNoInteractions(resources,grants,roles);
    }
    @Test void toolWriteRejectsForeignRoleEvenWhenOperatorManagesCurrentTenant(){
        when(access.user(request)).thenReturn("operator");when(access.tenant(request)).thenReturn("tenant");when(identities.hasPermission("operator","system:permission-center:manage")).thenReturn(true);
        var foreign=new SysRole();foreign.setTenantId("other");when(roles.findById("foreign")).thenReturn(Optional.of(foreign));var input=new com.chatchat.enterprise.entity.mcp.McpToolPermission();input.setTargetType("ROLE");input.setTargetId("foreign");input.setEffect("allow");input.setLocalToolName("tool");
        assertThatThrownBy(()->controller.save(request,input)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(grants,tools,audits);
    }
}
