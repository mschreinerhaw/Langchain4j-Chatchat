package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcpserver.admin.AdminAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonManagedInternalAuthFilterTest {
    @Test void ordinaryAdminCannotSubmitManagedDockerOperation() throws Exception {
        AdminAuthService auth = mock(AdminAuthService.class);
        InternalCredentialProperties credentials = new InternalCredentialProperties();
        when(auth.username("ordinary-token")).thenReturn("ordinary-admin");
        when(auth.username("internal-token")).thenReturn(credentials.resolvedUsername());
        PythonManagedInternalAuthFilter filter = new PythonManagedInternalAuthFilter(auth, credentials);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/python/runtime/operations");
        request.addHeader("Authorization", "Bearer ordinary-token");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(request, denied, (req, res) -> fail("Ordinary admin reached managed operation"));
        assertEquals(403, denied.getStatus());
        MockHttpServletRequest internal = new MockHttpServletRequest("POST", "/api/v1/python/runtime/operations");
        internal.addHeader("Authorization", "Bearer internal-token");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        boolean[] reached = {false};
        filter.doFilter(internal, allowed, (req, res) -> reached[0] = true);
        assertTrue(reached[0]);
    }
}
