package com.chatchat.mcpserver.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminAuthService authService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (!requiresAdminToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = resolveBearerToken(request);
        if (authService.isValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
            "code", 401,
            "message", "Unauthorized",
            "timestamp", System.currentTimeMillis()
        ));
    }

    private boolean requiresAdminToken(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/mcp-services/heartbeat")) {
            return false;
        }
        return path.startsWith("/api/v1/api-services")
            || path.startsWith("/api/v1/mcp-services")
            || path.startsWith("/api/v1/database-query")
            || path.startsWith("/api/v1/audit-logs");
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorization.substring(prefix.length()).trim();
    }
}
