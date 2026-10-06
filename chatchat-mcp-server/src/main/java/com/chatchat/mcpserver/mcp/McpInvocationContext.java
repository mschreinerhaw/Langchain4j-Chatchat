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
