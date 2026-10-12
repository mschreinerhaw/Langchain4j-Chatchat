package com.chatchat.api.workspace;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WorkspacePolicyFilterTest {
    final WorkspaceService spaces = mock(WorkspaceService.class);
    final WorkspaceResourceService resources = mock(WorkspaceResourceService.class);
    final WorkspacePolicyFilter filter = new WorkspacePolicyFilter(spaces, resources, new ObjectMapper());
    MockHttpServletRequest request(String path, String workspace) {
        var request = new MockHttpServletRequest("GET", path);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "canonical-user");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant");
        if (workspace != null) request.addHeader("X-Workspace-Id", workspace); return request;
    }
    @Test void forgedWorkspaceNeverReachesController() throws Exception {
        when(spaces.accessible("canonical-user", "forged")).thenThrow(new WorkspaceService.AccessDenied());
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        filter.doFilter(request("/api/v1/data-science/python/workbench", "forged"), response, chain);
        assertThat(response.getStatus()).isEqualTo(403); verifyNoInteractions(chain); assertThat(WorkspaceRequestContext.current()).isNull();
    }
    @Test void missingHeaderStillChecksObjectBindingAndNeverDefaultsNewObjectToLegacyAccess() throws Exception {
        doThrow(new WorkspaceService.AccessDenied()).when(resources).assertAccess(new WorkspaceRequestContext.Scope("tenant", "canonical-user", "zhice"), "task", "private-id");
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        filter.doFilter(request("/api/v1/agent/tasks/private-id/events/stream", null), response, chain);
        assertThat(response.getStatus()).isEqualTo(403); verifyNoInteractions(chain);
    }
    @Test void requestIdentityComesFromAuthenticatedAttributesAndScopeIsAlwaysCleared() throws Exception {
        var request = request("/api/v1/conversations/session-id", "zhice"); request.addParameter("userId", "other"); request.addParameter("tenantId", "other");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertThat(WorkspaceRequestContext.current())
            .isEqualTo(new WorkspaceRequestContext.Scope("tenant", "canonical-user", "zhice")));
        verify(resources).assertAccess(new WorkspaceRequestContext.Scope("tenant", "canonical-user", "zhice"), "session", "session-id");
        assertThat(WorkspaceRequestContext.current()).isNull();
    }
    @Test void registryRemainsAccessibleWhenSelectedWorkspaceIsDisabled() throws Exception {
        var chain = mock(FilterChain.class); filter.doFilter(request("/api/v1/workspaces", "disabled"), new MockHttpServletResponse(), chain);
        verifyNoInteractions(spaces); verify(chain).doFilter(any(), any()); assertThat(WorkspaceRequestContext.current()).isNull();
    }
    @Test void encodedArtifactIdCannotBypassPrivateObjectCheck() throws Exception {
        doThrow(new WorkspaceService.AccessDenied()).when(resources).assertAccess(new WorkspaceRequestContext.Scope("tenant", "canonical-user", "zhice"), "artifact", "private-id");
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        filter.doFilter(request("/api/v1/ui-artifacts/%70rivate-id/resources/answer", null), response, chain);
        assertThat(response.getStatus()).isEqualTo(403); verifyNoInteractions(chain);
    }
}
