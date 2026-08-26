package com.chatchat.api.service;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.exception.RuntimeScopeAccessDeniedException;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpServiceCall;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Binds transport authentication to the authoritative Kernel tenant and trace scope. */
@Service
public class ApiKernelScopeResolver {

    public McpServiceCall bind(McpServiceCall call, HttpServletRequest request) {
        if (call == null) throw new IllegalArgumentException("MCP service call is required");
        return call.withContext(governedContext(call.context(), call.requestId(), request));
    }

    public McpResultRepairRequest bind(McpResultRepairRequest repair, HttpServletRequest request) {
        if (repair == null) throw new IllegalArgumentException("MCP repair request is required");
        return repair.withContext(governedContext(repair.context(), repair.requestId(), request));
    }

    public KernelDataScope resolve(Map<String, Object> requestedContext, String requestId,
                                   HttpServletRequest request) {
        Map<String, Object> context = requestedContext == null ? Map.of() : requestedContext;
        String authenticatedTenant = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String requestedTenant = text(context.get("tenantId"));
        if (authenticatedTenant != null && requestedTenant != null
            && !authenticatedTenant.equals(requestedTenant)) {
            throw new RuntimeScopeAccessDeniedException(
                "Runtime Kernel tenant scope does not match authenticated tenant");
        }
        String authenticatedUser = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        return new KernelDataScope(firstText(authenticatedTenant, requestedTenant, "system"),
            firstText(authenticatedUser, text(context.get("userId"))), requestId,
            text(context.get("conversationId")), text(context.get("runId")),
            firstText(text(context.get("environment")), text(context.get("env"))),
            Map.of("source", "api-authentication-boundary"));
    }

    private Map<String, Object> governedContext(Map<String, Object> requestedContext, String requestId,
                                                HttpServletRequest request) {
        KernelDataScope scope = resolve(requestedContext, requestId, request);
        Map<String, Object> governed = new LinkedHashMap<>(requestedContext == null ? Map.of() : requestedContext);
        governed.put("tenantId", scope.tenantId());
        putOrRemove(governed, "userId", scope.userId());
        putOrRemove(governed, "conversationId", scope.conversationId());
        putOrRemove(governed, "runId", scope.runId());
        putOrRemove(governed, "environment", scope.environment());
        governed.put("scopeAuthority", "API_AUTHENTICATION_CONTEXT");
        return governed;
    }

    private void putOrRemove(Map<String, Object> values, String key, String value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    private String attribute(HttpServletRequest request, String name) {
        return request == null ? null : text(request.getAttribute(name));
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        if (values != null) for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }
}
