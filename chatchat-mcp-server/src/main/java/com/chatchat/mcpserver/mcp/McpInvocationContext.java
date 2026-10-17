package com.chatchat.mcpserver.mcp;

import com.chatchat.common.mcp.service.McpServiceCall;
import java.util.Map;
import java.util.Collection;

/**
 * Carries the inbound MCP caller context from the transport thread into tool execution/audit code.
 */
public final class McpInvocationContext {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private McpInvocationContext() {
    }

    public static Context current() {
        return CURRENT.get();
    }

    /** Restore the protocol envelope after the SDK switches away from the servlet thread. */
    public static Scope openRequestMeta(Map<String, Object> metadata) {
        if (current() != null) return open(current());
        Map<String, Object> meta = metadata == null ? Map.of() : metadata;
        Map<String, Object> user = nested(meta, "user");
        Map<String, Object> tenant = nested(meta, "tenant");
        Map<String, Object> scope = nested(meta, "scope");
        String userId = first(user, "userId", meta);
        return open(new Context(userId, null, null, text(meta, "requestId"),
            text(meta, "clientId"), userId, first(user, "username", meta),
            first(tenant, "tenantId", meta), first(user, "roles", meta),
            first(tenant, "workspaceId", meta), first(tenant, "env", meta),
            text(meta, "traceId"), first(scope, "assetType", meta),
            first(scope, "domain", meta), first(scope, "permissionLevel", meta),
            first(scope, "scopeExpression", meta)));
    }

    private static String first(Map<String, Object> nested, String key, Map<String, Object> meta) {
        String value = text(nested, key);
        return value == null ? text(meta, key) : value;
    }

    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((name, value) -> result.put(String.valueOf(name), value));
        return result;
    }

    /** Identity comes from the authenticated invocation envelope, never model arguments. */
    public static Scope openCall(McpServiceCall call) {
        Map<String, Object> values = call.context();
        return open(new Context(text(values, "userId"), null, null, call.requestId(),
            call.serviceId(), text(values, "userId"), text(values, "username"),
            text(values, "tenantId"), text(values, "roles"), text(values, "workspaceId"),
            text(values, "environment"), text(values, "traceId"), text(values, "assetType"),
            text(values, "domain"), text(values, "permissionLevel"), text(values, "scopeExpression")));
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        }
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    public static Scope open(Context context) {
        Context previous = CURRENT.get();
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        return new Scope(previous);
    }

    public record Context(
        String caller,
        String remoteAddr,
        String userAgent,
        String requestId,
        String clientId,
        String userId,
        String username,
        String tenantId,
        String roles,
        String workspaceId,
        String environment,
        String traceId,
        String assetType,
        String domain,
        String permissionLevel,
        String scopeExpression
    ) {
    }

    public static final class Scope implements AutoCloseable {

        private final Context previous;
        private boolean closed;

        private Scope(Context previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
