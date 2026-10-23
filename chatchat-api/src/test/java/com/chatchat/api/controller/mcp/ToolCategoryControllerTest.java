package com.chatchat.api.controller.mcp;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ToolCategoryControllerTest {
    @Test void mutationUsesAuthenticatedTenantExistingEditPrivilegeAndAudit() throws Exception {
        var client=mock(McpControlPlaneClient.class);var access=mock(ObjectAccessService.class);var identities=mock(EnterpriseAdminService.class);var workspace=mock(WorkspaceService.class);var audit=mock(SysAuditLogRepository.class);
        var request=new MockHttpServletRequest();when(access.user(request)).thenReturn("user");when(access.tenant(request)).thenReturn("tenant");
        var controller=new ToolCategoryController(client,access,identities,workspace,audit);
        var body=new ObjectMapper().readTree("{\"name\":\"分析\",\"tenantId\":\"other\"}");
        assertThatThrownBy(()->controller.create(request,body)).isInstanceOf(SecurityException.class);verifyNoInteractions(client,audit);
        when(identities.hasPermission("user","mcp:publication:edit")).thenReturn(true);
        when(identities.hasPermission("user","system:permission-center:manage")).thenReturn(true);
        when(client.request(anyString(),anyString(),any())).thenReturn(new ObjectMapper().readTree("{\"id\":\"category\"}"));
        controller.create(request,body);
        verify(client).request("POST","/api/v1/tool-categories?tenantId=tenant&actorId=user",body);
        verify(workspace,atLeastOnce()).requireAvailable(request,"mcp-publication");verify(audit).saveAndFlush(any());
    }
}
