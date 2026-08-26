package com.chatchat.api.service;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.exception.RuntimeScopeAccessDeniedException;
import com.chatchat.common.mcp.service.McpServiceCall;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKernelScopeResolverTest {
    private final ApiKernelScopeResolver resolver = new ApiKernelScopeResolver();

    @Test
    void bindsAuthenticatedTenantAndUserAsKernelAuthority() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-auth");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-auth");
        McpServiceCall call = new McpServiceCall(null, "request-1", "linux", "execute",
            Map.of(), Map.of(), 0);

        McpServiceCall governed = resolver.bind(call, request);

        assertThat(governed.context()).containsEntry("tenantId", "tenant-auth")
            .containsEntry("userId", "user-auth")
            .containsEntry("scopeAuthority", "API_AUTHENTICATION_CONTEXT");
    }

    @Test
    void rejectsTenantSpoofingBeforeKernelInvocation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-auth");
        McpServiceCall call = new McpServiceCall(null, "request-1", "linux", "execute",
            Map.of(), Map.of("tenantId", "tenant-other"), 0);

        assertThatThrownBy(() -> resolver.bind(call, request))
            .isInstanceOf(RuntimeScopeAccessDeniedException.class);
    }
}
