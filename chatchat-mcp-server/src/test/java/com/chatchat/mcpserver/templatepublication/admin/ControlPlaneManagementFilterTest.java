package com.chatchat.mcpserver.templatepublication.admin;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcpserver.admin.AdminAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ControlPlaneManagementFilterTest {
    @Test void browserAdminCannotPublishButInternalServiceCan() throws Exception {
        var auth=mock(AdminAuthService.class);var credentials=mock(InternalCredentialProperties.class);
        when(credentials.isEnabled()).thenReturn(true);when(credentials.resolvedUsername()).thenReturn("control-plane");
        when(auth.username("admin-token")).thenReturn("admin");when(auth.username("internal-token")).thenReturn("control-plane");
        var filter=new ControlPlaneManagementFilter(auth,credentials);
        for(String token:new String[]{"admin-token","internal-token"}) {
            var request=new MockHttpServletRequest("POST","/api/v1/tool-publications");request.addHeader("Authorization","Bearer "+token);
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(token.equals("admin-token")?403:200);
            assertThat(chain.getRequest()!=null).isEqualTo(token.equals("internal-token"));
        }
    }
    @Test void legacyBindingWritesCannotBypassTheVersionLifecycle() throws Exception {
        var auth=mock(AdminAuthService.class);var credentials=mock(InternalCredentialProperties.class);
        when(credentials.isEnabled()).thenReturn(true);when(credentials.resolvedUsername()).thenReturn("control-plane");when(auth.username("token")).thenReturn("control-plane");
        var request=new MockHttpServletRequest("POST","/api/v1/template-query-publications");request.addHeader("Authorization","Bearer token");
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();new ControlPlaneManagementFilter(auth,credentials).doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(410);assertThat(chain.getRequest()).isNull();
    }
}
