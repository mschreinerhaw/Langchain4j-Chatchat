package com.chatchat.mcpserver.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";
    private static final String LOGIN_PATH = "/api/v1/admin/auth/login";
    private static final String HEARTBEAT_PATH = "/api/v1/mcp-services/heartbeat";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminAuthService authService;
    private final ObjectMapper objectMapper;

    /**
     * Returns whether should not filter.
     *
     * @param request the request value
     * @return whether the condition is satisfied
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
            || !path.startsWith(API_PREFIX)
            || LOGIN_PATH.equals(path)
            || HEARTBEAT_PATH.equals(path);
    }

    /**
     * Performs the do filter internal operation.
     *
     * @param request the request value
     * @param response the response value
     * @param filterChain the filter chain value
     * @throws ServletException if the operation fails
     * @throws IOException if the operation fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String token = resolveBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (authService.isValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
            "code", 401,
            "message", "请先登录",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Resolves the bearer token.
     *
     * @param authorization the authorization value
     * @return the resolved bearer token
     */
    private String resolveBearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
