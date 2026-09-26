package com.chatchat.mcpserver.library;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Allows only the API gateway to invoke the MCP-owned document library. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DocumentInternalAuthenticationFilter extends OncePerRequestFilter {
    private final String token;

    public DocumentInternalAuthenticationFilter(
        @Value("${CHATCHAT_DOCUMENT_GATEWAY_TOKEN:}") String token) {
        this.token = token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/internal/api/v1/search") || path.startsWith("/internal/api/v1/search/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String received = request.getHeader("X-Document-Gateway-Token");
        boolean validToken = !token.isBlank() && received != null && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
        if (!validToken) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String tenantId = request.getHeader("X-Document-Tenant-Id");
        String userId = request.getHeader("X-Document-User-Id");
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        DocumentPrincipalContext.attach(request, tenantId, userId,
            request.getHeader("X-Document-Username"), request.getHeader("X-Document-Roles"),
            request.getHeader("X-Document-Permissions"));
        chain.doFilter(request, response);
    }
}
