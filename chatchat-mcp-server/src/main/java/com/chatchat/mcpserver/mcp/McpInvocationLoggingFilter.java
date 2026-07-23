package com.chatchat.mcpserver.mcp;

import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.chatchat.mcpserver.authorization.McpAuthorizationProperties;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.license.McpLicenseService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class McpInvocationLoggingFilter extends OncePerRequestFilter {

    private final InvocationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final McpAuthorizationProperties authorizationProperties;
    private final McpAuthorizationService authorizationService;
    private final McpLicenseService licenseService;

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
        CachedBodyRequestWrapper wrappedRequest = new CachedBodyRequestWrapper(request);
        McpInvocationContext.Context invocationContext = invocationContext(wrappedRequest);
        McpInvocationContext.Scope scope = McpInvocationContext.open(invocationContext);
        try {
            if (authorizationProperties.isRequireTenantContext() && isToolCall(wrappedRequest, requestBody(wrappedRequest))
                && (invocationContext.tenantId() == null || invocationContext.tenantId().isBlank())) {
                writeTenantRequired(response);
                recordAudit(wrappedRequest, response, 0L, "TENANT_REQUIRED");
                return;
            }
            McpAuthorizationService.AuthorizationDecision authorization = authorizeToolCalls(requestBodyJson(wrappedRequest));
            if (!authorization.allowed()) {
                writeAuthorizationDenied(response, authorization.reason());
                recordAudit(wrappedRequest, response, 0L, authorization.reason());
                return;
            }
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
        } finally {
            scope.close();
        }
    }

    private McpInvocationContext.Context invocationContext(HttpServletRequest request) {
        JsonNode body = requestBodyJson(request);
        return new McpInvocationContext.Context(
            firstText(
                request.getHeader("X-User-Id"),
                request.getHeader("X-User-Name"),
                request.getHeader("X-Username"),
                request.getHeader("X-Caller"),
                request.getHeader("X-Operator-Id"),
                request.getHeader("X-Operator"),
                request.getHeader("X-Forwarded-User"),
                request.getRemoteUser(),
                request.getRemoteAddr()
            ),
            request.getRemoteAddr(),
            request.getHeader("User-Agent"),
            firstText(
                request.getHeader("X-Request-Id"),
                request.getHeader("X-Correlation-Id"),
                request.getHeader("Traceparent"),
                textAt(body, "traceId"),
                textAt(body, "params", "_meta", "traceId"),
                textAt(body, "params", "context", "traceId")
            ),
            firstText(
                request.getHeader("X-Client-Id"),
                request.getHeader("MCP-Client-Id")
            ),
            firstText(
                request.getHeader("X-User-Id"),
                request.getHeader("X-Operator-Id"),
                request.getHeader("X-Principal-Id")
            ),
            firstText(
                request.getHeader("X-Username"),
                request.getHeader("X-User-Name"),
                request.getHeader("X-Operator"),
                request.getHeader("X-Forwarded-User")
            ),
            firstText(
                request.getHeader("X-Tenant-Id"),
                request.getHeader("X-Tenant"),
                textAt(body, "tenant", "tenantId"),
                textAt(body, "params", "_meta", "tenant", "tenantId"),
                textAt(body, "params", "_meta", "tenantId"),
                textAt(body, "params", "context", "tenant", "tenantId"),
                textAt(body, "params", "context", "tenantId"),
                textAt(body, "params", "arguments", "tenant", "tenantId"),
                textAt(body, "params", "arguments", "mcpContext", "tenant", "tenantId"),
                textAt(body, "params", "arguments", "tenantId"),
                textAt(body, "params", "arguments", "tenant_id")
            ),
            firstText(
                request.getHeader("X-Roles"),
                request.getHeader("X-Role-Ids"),
                textAt(body, "user", "roles"),
                textAt(body, "params", "_meta", "user", "roles"),
                textAt(body, "params", "arguments", "roles")
            ),
            firstText(
                request.getHeader("X-Workspace-Id"),
                textAt(body, "tenant", "workspaceId"),
                textAt(body, "params", "_meta", "tenant", "workspaceId"),
                textAt(body, "params", "arguments", "workspaceId")
            ),
            firstText(
                request.getHeader("X-Environment"),
                request.getHeader("X-Env"),
                textAt(body, "tenant", "env"),
                textAt(body, "params", "_meta", "tenant", "env"),
                textAt(body, "params", "arguments", "env")
            ),
            firstText(
                request.getHeader("X-Trace-Id"),
                request.getHeader("X-Request-Id"),
                textAt(body, "traceId"),
                textAt(body, "params", "_meta", "traceId")
            ),
            firstText(
                request.getHeader("X-MCP-Asset-Type"),
                textAt(body, "scope", "assetType"),
                textAt(body, "params", "_meta", "scope", "assetType"),
                textAt(body, "params", "context", "scope", "assetType"),
                textAt(body, "params", "arguments", "scope", "assetType"),
                textAt(body, "params", "arguments", "assetType")
            ),
            firstText(
                request.getHeader("X-MCP-Domain"),
                textAt(body, "scope", "domain"),
                textAt(body, "params", "_meta", "scope", "domain"),
                textAt(body, "params", "context", "scope", "domain"),
                textAt(body, "params", "arguments", "scope", "domain"),
                textAt(body, "params", "arguments", "domain")
            ),
            firstText(
                request.getHeader("X-MCP-Permission-Level"),
                textAt(body, "scope", "permissionLevel"),
                textAt(body, "params", "_meta", "scope", "permissionLevel"),
                textAt(body, "params", "context", "scope", "permissionLevel"),
                textAt(body, "params", "arguments", "scope", "permissionLevel"),
                textAt(body, "params", "arguments", "permissionLevel")
            ),
            firstText(
                request.getHeader("X-MCP-Scope"),
                textAt(body, "scopeExpression"),
                textAt(body, "params", "_meta", "scopeExpression"),
                textAt(body, "params", "context", "scopeExpression"),
                textAt(body, "params", "arguments", "scopeExpression"),
                textAt(body, "params", "arguments", "router", "scopeExpression")
            )
        );
    }

    private boolean isToolCall(HttpServletRequest request, String requestBody) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        JsonNode root = requestBody == null ? requestBodyJson(request) : parseJson(requestBody);
        return containsToolCall(root);
    }

    private boolean containsToolCall(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsToolCall(item)) {
                    return true;
                }
            }
            return false;
        }
        return "tools/call".equals(text(node.get("method")));
    }

    private void writeTenantRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
            "success", false,
            "error", "TENANT_REQUIRED",
            "message", "missing tenantId in MCP request context"
        ));
    }

    private McpAuthorizationService.AuthorizationDecision authorizeToolCalls(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return McpAuthorizationService.AuthorizationDecision.allowDecision();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                McpAuthorizationService.AuthorizationDecision decision = authorizeToolCalls(item);
                if (!decision.allowed()) {
                    return decision;
                }
            }
            return McpAuthorizationService.AuthorizationDecision.allowDecision();
        }
        if (!"tools/call".equals(text(node.get("method")))) {
            return McpAuthorizationService.AuthorizationDecision.allowDecision();
        }
        JsonNode params = node.path("params");
        String toolName = firstText(params.get("name"), params.get("toolName"), params.get("tool_name"));
        JsonNode argumentsNode = firstNode(params.get("arguments"), params.get("input"));
        String licenseDenialReason = licenseService.toolDenialReason(toolName);
        if (licenseDenialReason != null) {
            return McpAuthorizationService.AuthorizationDecision.denyDecision(
                licenseDenialReason + ": " + toolName);
        }
        Map<String, Object> arguments = argumentsNode != null && argumentsNode.isObject()
            ? objectMapper.convertValue(argumentsNode, new TypeReference<Map<String, Object>>() { })
            : new LinkedHashMap<>();
        return authorizationService.authorize(toolName, arguments);
    }

    private void writeAuthorizationDenied(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        boolean licenseDenied = reason != null && (reason.contains("License") || reason.contains("授权 MCP"));
        objectMapper.writeValue(response.getWriter(), Map.of(
            "success", false,
            "error", licenseDenied ? "MCP_LICENSE_ACCESS_DENIED" : "MCP_ASSET_ACCESS_DENIED",
            "message", reason == null || reason.isBlank() ? "MCP asset access denied" : reason
        ));
    }

    /**
     * Performs the record audit operation.
     *
     * @param request the request value
     * @param response the response value
     * @param durationMs the duration ms value
     * @param errorMessage the error message value
     */
    private void recordAudit(CachedBodyRequestWrapper request, HttpServletResponse response, long durationMs, String errorMessage) {
        auditService.recordMcpTransportRequest(
            request.getMethod(),
            request.getRequestURI(),
            request.getQueryString(),
            invocationCaller(request),
            request.getHeader("User-Agent"),
            response.getStatus(),
            durationMs,
            errorMessage,
            requestBody(request)
        );
    }

    private String invocationCaller(HttpServletRequest request) {
        McpInvocationContext.Context context = McpInvocationContext.current();
        return firstText(context == null ? null : context.caller(), request.getRemoteAddr());
    }

    /**
     * Performs the request body operation.
     *
     * @param request the request value
     * @return the operation result
     */
    private String requestBody(CachedBodyRequestWrapper request) {
        byte[] content = request.cachedBody();
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

    private JsonNode requestBodyJson(HttpServletRequest request) {
        if (request instanceof CachedBodyRequestWrapper wrapper) {
            return parseJson(requestBody(wrapper));
        }
        return null;
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private String textAt(JsonNode root, String... path) {
        JsonNode current = root;
        if (current == null || path == null) {
            return null;
        }
        for (String key : path) {
            current = current == null ? null : current.get(key);
            if (current == null || current.isNull() || current.isMissingNode()) {
                return null;
            }
        }
        if (current.isArray()) {
            List<String> values = new ArrayList<>();
            current.forEach(item -> {
                String text = text(item);
                if (text != null && !text.isBlank()) {
                    values.add(text);
                }
            });
            return values.isEmpty() ? null : String.join(",", values);
        }
        return text(current);
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

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
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

    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        private CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        private byte[] cachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    if (listener == null) {
                        return;
                    }
                    try {
                        listener.onDataAvailable();
                        listener.onAllDataRead();
                    } catch (IOException ex) {
                        listener.onError(ex);
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null || getCharacterEncoding().isBlank()
                ? StandardCharsets.UTF_8
                : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
