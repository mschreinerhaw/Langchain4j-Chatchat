package com.chatchat.mcpserver.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Enforces signed menu entitlements on the administration APIs owned by each menu. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@RequiredArgsConstructor
public class McpMenuLicenseFilter extends OncePerRequestFilter {
    private final McpLicenseService licenseService;
    private final McpAdminMenuCatalog menuCatalog;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
            || menuCatalog.menuForPath(path(request)).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var menu = menuCatalog.menuForPath(path(request)).orElseThrow();
        var status = licenseService.status();
        if (menuCatalog.authorized(status, menu.key())) {
            chain.doFilter(request, response);
            return;
        }
        String errorCode = status != null && "EXPIRED".equalsIgnoreCase(status.status())
            ? "MCP_LICENSE_EXPIRED" : "MCP_MENU_NOT_LICENSED";
        String message = status == null || !status.valid()
            ? (status == null ? "License 无效，禁止访问 MCP 管理接口" : status.message())
            : "License 未授权菜单模块: " + menu.label();
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", HttpStatus.FORBIDDEN.value());
        body.put("errorCode", errorCode);
        body.put("message", message);
        body.put("menu", menu.key());
        body.put("licenseStatus", status == null ? "INVALID" : status.status());
        objectMapper.writeValue(response.getWriter(), body);
    }

    private String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
