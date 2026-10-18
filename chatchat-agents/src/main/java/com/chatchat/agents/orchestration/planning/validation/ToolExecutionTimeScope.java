package com.chatchat.agents.orchestration.planning.validation;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/** A bounded tool wait does not consume the sequential caller's active execution time.
 * Reserve at most the tool's remaining lifetime, then refund unused time even on failure.
 * AtomicLong survives shallow attribute copies and serializes as an ordinary deadline number.
 */
public final class ToolExecutionTimeScope implements AutoCloseable {
    private final AtomicLong parentDeadline;
    private final long reservedMillis;
    private final long startedNanos = System.nanoTime();
    private boolean closed;

    private ToolExecutionTimeScope(AtomicLong parentDeadline, long reservedMillis) {
        this.parentDeadline = parentDeadline;
        this.reservedMillis = reservedMillis;
        if (parentDeadline != null) parentDeadline.addAndGet(reservedMillis);
    }

    public static ToolExecutionTimeScope open(Map<String, Object> attributes, long toolDeadlineAt) {
        Object value = attributes.get("__agentDeadlineAt");
        long remaining = Math.max(0, toolDeadlineAt - System.currentTimeMillis());
        return new ToolExecutionTimeScope(value instanceof AtomicLong deadline && deadline.get() > 0
            ? deadline : null, remaining);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (parentDeadline != null) parentDeadline.addAndGet(-Math.max(0, reservedMillis - elapsed));
    }
}
