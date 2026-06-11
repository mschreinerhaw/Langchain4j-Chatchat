package com.chatchat.api.security;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
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

@Component
@RequiredArgsConstructor
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";
    private static final String LOGIN_PATH = "/api/v1/enterprise/auth/login";
    private static final String BEARER_PREFIX = "Bearer ";

    private final EnterpriseAdminService adminService;
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
            || LOGIN_PATH.equals(path);
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
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!adminService.isTokenValid(token)) {
            writeUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Resolves the bearer token.
     *
     * @param authorization the authorization value
     * @return the resolved bearer token
     */
    private String resolveBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return "";
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * Writes the unauthorized.
     *
     * @param response the response value
     * @throws IOException if the operation fails
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(401, "请先登录"));
    }
}
