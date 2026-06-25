package com.chatchat.mcpserver.mcp;

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
        String clientId
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
