package com.chatchat.mcpserver.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs inbound MCP transport requests so tool-call reachability is visible.
 */
@Slf4j
@Component
public class McpInvocationLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/mcp")) {
            filterChain.doFilter(request, response);
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
            log.info("MCP transport request completed method={} uri={} status={} durationMs={} remote={}",
                request.getMethod(),
                uri,
                response.getStatus(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                request.getRemoteAddr());
        } catch (ServletException | IOException | RuntimeException ex) {
            log.warn("MCP transport request failed method={} uri={} status={} durationMs={} remote={} error={}",
                request.getMethod(),
                uri,
                response.getStatus(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                request.getRemoteAddr(),
                ex.getMessage(),
                ex);
            throw ex;
        }
    }
}
