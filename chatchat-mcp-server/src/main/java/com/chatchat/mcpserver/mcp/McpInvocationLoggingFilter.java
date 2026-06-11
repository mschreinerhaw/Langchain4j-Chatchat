package com.chatchat.mcpserver.mcp;

import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs inbound MCP transport requests so tool-call reachability is visible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpInvocationLoggingFilter extends OncePerRequestFilter {

    private final InvocationAuditService auditService;
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
            String body = requestBody(wrappedRequest);
            log.info("MCP transport request completed method={} uri={} status={} durationMs={} remote={} rpc={}",
                wrappedRequest.getMethod(),
                uri,
                response.getStatus(),
                durationMs,
                wrappedRequest.getRemoteAddr(),
                mcpRequestSummary(body));
            recordAudit(wrappedRequest, response, durationMs, null);
        } catch (ServletException | IOException | RuntimeException ex) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            String body = requestBody(wrappedRequest);
            log.warn("MCP transport request failed method={} uri={} status={} durationMs={} remote={} rpc={} error={}",
                wrappedRequest.getMethod(),
                uri,
                response.getStatus(),
                durationMs,
                wrappedRequest.getRemoteAddr(),
                mcpRequestSummary(body),
                ex.getMessage(),
                ex);
            recordAudit(wrappedRequest, response, durationMs, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Performs the record audit operation.
     *
     * @param request the request value
     * @param response the response value
     * @param durationMs the duration ms value
     * @param errorMessage the error message value
     */
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

    /**
     * Performs the request body operation.
     *
     * @param request the request value
     * @return the operation result
     */
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

    /**
     * Performs the mcp request summary operation.
     *
     * @param requestBody the request body value
     * @return the operation result
     */
    private Object mcpRequestSummary(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            if (root.isArray()) {
                List<Object> items = new ArrayList<>();
                for (JsonNode item : root) {
                    items.add(singleRequestSummary(item));
                }
                return ToolLogSummarizer.summarize(items);
            }
            return ToolLogSummarizer.summarize(singleRequestSummary(root));
        } catch (Exception ex) {
            return Map.of("bodyChars", requestBody.length(), "parseError", ex.getMessage());
        }
    }

    /**
     * Performs the single request summary operation.
     *
     * @param node the node value
     * @return the operation result
     */
    private Map<String, Object> singleRequestSummary(JsonNode node) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", text(node.get("id")));
        summary.put("method", text(node.get("method")));
        JsonNode params = node.get("params");
        if (params != null && params.isObject()) {
            summary.put("toolName", firstText(params.get("name"), params.get("toolName"), params.get("tool_name")));
            JsonNode arguments = firstNode(params.get("arguments"), params.get("input"));
            if (arguments != null && !arguments.isNull()) {
                summary.put("arguments", objectMapper.convertValue(arguments, Object.class));
            }
        }
        return summary;
    }

    /**
     * Performs the first node operation.
     *
     * @param nodes the nodes value
     * @return the operation result
     */
    private JsonNode firstNode(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull() && !node.isMissingNode()) {
                return node;
            }
        }
        return null;
    }

    /**
     * Performs the first text operation.
     *
     * @param nodes the nodes value
     * @return the operation result
     */
    private String firstText(JsonNode... nodes) {
        JsonNode node = firstNode(nodes);
        return text(node);
    }

    /**
     * Performs the text operation.
     *
     * @param node the node value
     * @return the operation result
     */
    private String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
