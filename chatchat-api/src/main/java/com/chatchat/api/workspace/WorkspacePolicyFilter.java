package com.chatchat.api.workspace;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.workspace.*;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component @RequiredArgsConstructor @Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class WorkspacePolicyFilter extends OncePerRequestFilter {
    @org.springframework.beans.factory.annotation.Value("${chatchat.workspace.enabled:true}")
    private boolean enabled = true;
    private final WorkspaceService spaces;
    private final WorkspaceResourceService resources;
    private final ObjectMapper mapper;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        var user = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        var tenant = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        if (user == null || tenant == null) { chain.doFilter(request, response); return; }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String header = request.getHeader("X-Workspace-Id");
        String workspace = header == null || header.isBlank() ? "zhice" : header;
        try {
            if (!enabled && !"zhice".equals(workspace)) throw new WorkspaceService.AccessDenied();
            // Control-plane requests must remain usable even when the selected workspace is disabled.
            boolean agentApiToken = "agent_api_token".equals(request.getAttribute(ApiAuthenticationFilter.AUTHENTICATION_TYPE));
            if (enabled && !agentApiToken && !path.startsWith("/api/v1/workspaces") && !path.startsWith("/api/v1/enterprise/")
                && !path.startsWith("/api/v1/health"))
                spaces.accessible(String.valueOf(user), workspace);
            var scope = new WorkspaceRequestContext.Scope(String.valueOf(tenant), String.valueOf(user), workspace);
            WorkspaceRequestContext.set(scope);
            path = org.springframework.web.util.UriUtils.decode(path, java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = path.split("/");
            if (parts.length >= 6 && path.startsWith("/api/v1/agent/tasks/")
                && !java.util.Set.of("runtime", "todos", "experiences").contains(parts[5])) resources.assertAccess(scope, "task", parts[5]);
            if (parts.length >= 5 && path.startsWith("/api/v1/conversations/") && !"user".equals(parts[4]))
                resources.assertAccess(scope, "session", parts[4]);
            if (parts.length >= 5 && path.startsWith("/api/v1/ui-artifacts/")) resources.assertAccess(scope, "artifact", parts[4]);
            chain.doFilter(request, response);
        } catch (WorkspaceService.AccessDenied denied) {
            response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getWriter(), ApiResponse.error(403, "Workspace or resource access denied"));
        } finally { WorkspaceRequestContext.clear(); }
    }
}
