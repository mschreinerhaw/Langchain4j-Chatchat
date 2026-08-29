package com.chatchat.api.security;

import com.chatchat.api.config.RequestCorrelationFilter;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.service.AgentApiTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "chatchat.api.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER_ID = "chatchat.currentUserId";
    public static final String CURRENT_USERNAME = "chatchat.currentUsername";
    public static final String CURRENT_TENANT_ID = "chatchat.currentTenantId";
    public static final String AUTHENTICATION_TYPE = "chatchat.authenticationType";
    public static final String AGENT_API_TOKEN_ID = "chatchat.agentApiTokenId";

    private static final String API_PREFIX = "/api/v1/";
    private static final String LOGIN_PATH = "/api/v1/enterprise/auth/login";
    private static final String BEARER_PREFIX = "Bearer ";

    private final EnterpriseAdminService adminService;
    private final AgentApiTokenService agentApiTokenService;
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
        EnterpriseAdminService.UserView user;
        try {
            if (agentApiTokenService.looksLikeApiToken(token)) {
                String path = applicationPath(request);
                if (!isAgentApiInvocationPath(path)) {
                    writeUnauthorized(request, response, "Agent API Token 只能用于已发布 Agent 的问答接口");
                    return;
                }
                AgentApiTokenService.Authentication authentication = agentApiTokenService.authenticate(
                    token, request.getRemoteAddr(), path);
                if (authentication == null) {
                    writeUnauthorized(request, response, "Agent API Token 无效、已过期或已撤销");
                    return;
                }
                user = adminService.getUserView(authentication.userId());
                request.setAttribute(AUTHENTICATION_TYPE, "agent_api_token");
                request.setAttribute(AGENT_API_TOKEN_ID, authentication.tokenId());
            } else {
                user = adminService.resolveSessionByToken(token)
                    .filter(candidate -> "enabled".equalsIgnoreCase(candidate.status()))
                    .orElse(null);
                request.setAttribute(AUTHENTICATION_TYPE, "session");
            }
        } catch (RuntimeException ex) {
            writeUnauthorized(request, response, "身份凭证无效");
            return;
        }
        if (user == null) {
            writeUnauthorized(request, response, "请先登录");
            return;
        }
        request.setAttribute(CURRENT_USER_ID, user.id());
        request.setAttribute(CURRENT_USERNAME, user.username());
        request.setAttribute(CURRENT_TENANT_ID, user.tenantId());
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
    private void writeUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.error(401, message);
        Object requestId = request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId != null) {
            body.setRequestId(String.valueOf(requestId));
        }
        objectMapper.writeValue(response.getWriter(), body);
    }

    private String applicationPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private boolean isAgentApiInvocationPath(String path) {
        return path.matches("/api/v1/published-agents/[^/]+/questions/?")
            || path.matches("/api/v1/published-agents/[^/]+/questions/[^/]+/(status|answer)/?");
    }
}
