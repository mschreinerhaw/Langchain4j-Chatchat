package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcpserver.admin.AdminAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/** Managed operation APIs are internal Runtime commands, not administrator UI actions. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
@RequiredArgsConstructor
public class PythonManagedInternalAuthFilter extends OncePerRequestFilter {
    private final AdminAuthService auth;
    private final InternalCredentialProperties credentials;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/v1/python/runtime/operations");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : null;
        String actor = auth.username(token);
        if (actor == null || !Objects.equals(actor, credentials.resolvedUsername())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Internal Runtime credential required");
            return;
        }
        chain.doFilter(request, response);
    }
}
