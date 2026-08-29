package com.chatchat.api.security;

import com.chatchat.enterprise.service.AgentApiTokenService;
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
class ApiAuthenticationFilterTest {

    @Mock private EnterpriseAdminService adminService;
    @Mock private AgentApiTokenService tokenService;
    @Mock private FilterChain chain;

    private ApiAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiAuthenticationFilter(adminService, tokenService, new ObjectMapper());
    }

    @Test
    void rejectsAgentApiTokenOnManagementEndpoint() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v1/enterprise/agent-api-tokens", "ccat_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenService.looksLikeApiToken("ccat_secret")).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(tokenService, never()).authenticate("ccat_secret", null, "/api/v1/enterprise/agent-api-tokens");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void acceptsAgentApiTokenForPublishedAgentStatusAndSetsIdentity() throws Exception {
        String path = "/api/v1/published-agents/demo/questions/task-1/status";
        MockHttpServletRequest request = request("GET", path, "ccat_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenService.looksLikeApiToken("ccat_secret")).thenReturn(true);
        when(tokenService.authenticate("ccat_secret", "127.0.0.1", path))
            .thenReturn(new AgentApiTokenService.Authentication("token-id", "user-id", "alice", "tenant-id"));
        when(adminService.getUserView("user-id")).thenReturn(new EnterpriseAdminService.UserView(
            "user-id", "tenant-id", 100001L, "Tenant", null, "alice", "Alice", null, null,
            "enabled", null, List.of("role-id"), List.of(), Instant.now(), Instant.now(), false));

        filter.doFilterInternal(request, response, chain);

        assertThat(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).isEqualTo("user-id");
        assertThat(request.getAttribute(ApiAuthenticationFilter.AUTHENTICATION_TYPE)).isEqualTo("agent_api_token");
        verify(chain).doFilter(request, response);
    }

    @Test
    void legacyEmbedLoginIsNoLongerPublic() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/enterprise/auth/embed-login", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenService.looksLikeApiToken("")).thenReturn(false);
        when(adminService.resolveSessionByToken("")).thenReturn(java.util.Optional.empty());

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    private MockHttpServletRequest request(String method, String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        if (token != null && !token.isBlank()) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }
}
