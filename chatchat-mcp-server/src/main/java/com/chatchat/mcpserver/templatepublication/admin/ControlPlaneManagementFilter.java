package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcpserver.admin.AdminAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Objects;

/** Management service credentials stay on the API server. Browser admin tokens cannot publish. */
@Component @RequiredArgsConstructor @Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class ControlPlaneManagementFilter extends OncePerRequestFilter {
    private final AdminAuthService auth;
    private final InternalCredentialProperties credentials;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/v1/template-query-publications") && !path.startsWith("/api/v1/tool-publications");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        if (!credentials.isEnabled() || !Objects.equals(auth.username(token), credentials.resolvedUsername())) {
            response.sendError(403, "Control plane service identity required"); return;
        }
        // Legacy binding edits bypass version review. Keep only read catalog endpoints.
        if (request.getRequestURI().contains("/template-query-publications") && !"GET".equals(request.getMethod())) {
            response.sendError(410, "Use versioned tool publication"); return;
        }
        chain.doFilter(request, response);
    }
}
