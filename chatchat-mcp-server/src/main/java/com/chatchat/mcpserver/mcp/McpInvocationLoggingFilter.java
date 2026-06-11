package com.chatchat.mcpserver.mcp;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Logs inbound MCP transport requests so tool-call reachability is visible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpInvocationLoggingFilter extends OncePerRequestFilter {

    private final InvocationAuditService auditService;

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
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        try {
            filterChain.doFilter(wrappedRequest, response);
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log.info("MCP transport request completed method={} uri={} status={} durationMs={} remote={}",
                wrappedRequest.getMethod(),
                uri,
                response.getStatus(),
                durationMs,
                wrappedRequest.getRemoteAddr());
            recordAudit(wrappedRequest, response, durationMs, null);
        } catch (ServletException | IOException | RuntimeException ex) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log.warn("MCP transport request failed method={} uri={} status={} durationMs={} remote={} error={}",
                wrappedRequest.getMethod(),
                uri,
                response.getStatus(),
                durationMs,
                wrappedRequest.getRemoteAddr(),
                ex.getMessage(),
                ex);
            recordAudit(wrappedRequest, response, durationMs, ex.getMessage());
            throw ex;
        }
    }

    private void recordAudit(ContentCachingRequestWrapper request, HttpServletResponse response, long durationMs, String errorMessage) {
        auditService.recordMcpTransportRequest(
            request.getMethod(),
            request.getRequestURI(),
            request.getQueryString(),
            request.getRemoteAddr(),
            request.getHeader("User-Agent"),
            response.getStatus(),
            durationMs,
            errorMessage,
            requestBody(request)
        );
    }

    private String requestBody(ContentCachingRequestWrapper request) {
        byte[] content = request.getContentAsByteArray();
        if (content.length == 0) {
            return null;
        }
        try {
            String encoding = request.getCharacterEncoding();
            Charset charset = encoding == null || encoding.isBlank()
                ? StandardCharsets.UTF_8
                : Charset.forName(encoding);
            return new String(content, charset);
        } catch (Exception ex) {
            return new String(content, StandardCharsets.UTF_8);
        }
    }
}
