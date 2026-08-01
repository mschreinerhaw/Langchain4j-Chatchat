package com.chatchat.common.concurrent;

import java.util.concurrent.CancellationException;

/** Preserves cooperative cancellation across runtime and tool boundaries. */
public final class CancellationSupport {

    private CancellationSupport() {
    }

    public static void throwIfCancelled(String operation) {
        if (Thread.currentThread().isInterrupted()) {
            throw cancelled(operation, null);
        }
    }

    public static void rethrowIfCancelled(Throwable failure, String operation) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw cancelled(operation, failure);
            }
            if (current instanceof CancellationException cancellation) {
                throw cancellation;
            }
            current = current.getCause();
        }
        throwIfCancelled(operation);
    }

    public static CancellationException cancelled(String operation, Throwable cause) {
        CancellationException cancellation = new CancellationException(operation + " was cancelled");
        if (cause != null) {
            cancellation.initCause(cause);
        }
        return cancellation;
    }
}
