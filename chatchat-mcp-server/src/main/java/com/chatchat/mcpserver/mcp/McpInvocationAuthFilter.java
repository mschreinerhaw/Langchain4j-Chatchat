package com.chatchat.mcpserver.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class McpInvocationAuthFilter extends OncePerRequestFilter {

    private final McpSecurityProperties securityProperties;
    private final McpServiceRegistryService registryService;
    private final ObjectMapper objectMapper;

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
        if (!securityProperties.isRequireMcpToken() || !request.getRequestURI().startsWith("/mcp")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = resolveInvocationToken(request);
        if (securityProperties.matchesInvocationToken(token) || registryService.isValidToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
            "code", 403,
            "message", "Invalid X-MCP-TOKEN",
            "timestamp", System.currentTimeMillis()
        ));
    }

    private String resolveInvocationToken(HttpServletRequest request) {
        String token = request.getHeader("X-MCP-TOKEN");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String bearerPrefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, bearerPrefix, 0, bearerPrefix.length())) {
            return null;
        }
        String bearerToken = authorization.substring(bearerPrefix.length()).trim();
        return bearerToken.isBlank() ? null : bearerToken;
    }
}
