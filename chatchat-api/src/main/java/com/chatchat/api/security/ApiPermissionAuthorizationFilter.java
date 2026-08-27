package com.chatchat.api.security;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Enforces maintained API resource permissions after authentication.
 */
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "chatchat.api.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApiPermissionAuthorizationFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";

    private final EnterpriseAdminService adminService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || !path.startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object userId = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (userId == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = applicationPath(request);
        String method = request.getMethod();
        List<ResourcePermission> matches = adminService.listPermissions().stream()
            .filter(permission -> "enabled".equalsIgnoreCase(permission.getStatus()))
            .filter(permission -> methodMatches(permission.getHttpMethod(), method))
            .map(permission -> new ResourcePermission(permission, apiPattern(permission.getResourcePath())))
            .filter(candidate -> candidate.pattern() != null && pathMatcher.match(candidate.pattern(), path))
            .sorted(Comparator.comparing(ResourcePermission::pattern, pathMatcher.getPatternComparator(path)))
            .toList();
        if (matches.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        ResourcePermission required = matches.get(0);
        List<String> permissionCodes = adminService.getUserView(String.valueOf(userId)).permissionCodes();
        if (permissionCodes.contains(required.permission().getPermissionCode())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(403, "无权访问该资源"));
    }

    private boolean methodMatches(String configured, String requested) {
        return configured == null || configured.isBlank() || "*".equals(configured)
            || configured.toUpperCase(Locale.ROOT).equals(requested.toUpperCase(Locale.ROOT));
    }

    private String apiPattern(String resourcePath) {
        if (resourcePath == null || !resourcePath.startsWith(API_PREFIX)) {
            return null;
        }
        if (resourcePath.contains("*") || resourcePath.contains("?")) {
            return resourcePath;
        }
        return resourcePath.endsWith("/") ? resourcePath + "**" : resourcePath + "/**";
    }

    private String applicationPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private record ResourcePermission(SysPermission permission, String pattern) {
    }
}
