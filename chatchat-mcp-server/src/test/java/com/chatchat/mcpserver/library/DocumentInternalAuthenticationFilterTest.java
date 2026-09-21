package com.chatchat.mcpserver.library;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentInternalAuthenticationFilterTest {
    @Test
    void requiresGatewayTokenAndIdentity() throws Exception {
        DocumentInternalAuthenticationFilter filter = new DocumentInternalAuthenticationFilter("secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/api/v1/search/library");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(request, denied, (ignoredRequest, ignoredResponse) -> { });
        assertThat(denied.getStatus()).isEqualTo(403);

        request.addHeader("X-Document-Gateway-Token", "secret");
        MockHttpServletResponse missingIdentity = new MockHttpServletResponse();
        filter.doFilter(request, missingIdentity, (ignoredRequest, ignoredResponse) -> { });
        assertThat(missingIdentity.getStatus()).isEqualTo(401);

        request.addHeader("X-Document-Tenant-Id", "tenant-1");
        request.addHeader("X-Document-User-Id", "user-1");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(request, allowed, (ignoredRequest, ignoredResponse) ->
            assertThat(ignoredRequest.getAttribute(DocumentPrincipalContext.CURRENT_USER_ID)).isEqualTo("user-1"));
        assertThat(allowed.getStatus()).isEqualTo(200);
    }
}
