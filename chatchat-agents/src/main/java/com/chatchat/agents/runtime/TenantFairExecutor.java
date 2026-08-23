package com.chatchat.agents.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Bounded round-robin admission layer over the shared Agent executor.
 * A noisy tenant cannot consume every delegated slot or every queue entry.
 */
final class TenantFairExecutor {

    private final Executor delegate;
    private final int maxConcurrentPerTenant;
    private final int maxQueuedPerTenant;
    private final int maxQueuedTotal;
    private final boolean enabled;
    private final Map<String, TenantQueue> tenants = new LinkedHashMap<>();
    private final Deque<String> roundRobin = new ArrayDeque<>();
    private int queuedTotal;

    TenantFairExecutor(Executor delegate, AgentRuntimeProperties properties) {
        this.delegate = delegate;
        AgentRuntimeProperties configured = properties == null ? new AgentRuntimeProperties() : properties;
        this.maxConcurrentPerTenant = configured.maxConcurrentPerTenant();
        this.maxQueuedPerTenant = configured.maxQueuedPerTenant();
        this.maxQueuedTotal = configured.queueCapacity();
        this.enabled = configured.isTenantFairSchedulingEnabled();
    }

    void execute(String tenantId, Runnable task) {
        execute(tenantId, task, failure -> {
            throw failure;
        });
    }

    void execute(String tenantId, Runnable task, Consumer<RejectedExecutionException> rejectionHandler) {
        Consumer<RejectedExecutionException> safeHandler = rejectionHandler == null
            ? failure -> { throw failure; }
            : rejectionHandler;
        if (!enabled) {
            try {
                delegate.execute(task);
            } catch (RejectedExecutionException ex) {
                safeHandler.accept(ex);
            }
            return;
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        synchronized (this) {
            TenantQueue queue = tenants.computeIfAbsent(tenant, ignored -> new TenantQueue());
            if (queue.waiting.size() >= maxQueuedPerTenant) {
                throw new RejectedExecutionException("Tenant Agent queue is full: " + tenant);
            }
            if (queuedTotal >= maxQueuedTotal) {
                throw new RejectedExecutionException("Global Agent queue is full");
            }
            queue.waiting.addLast(new QueuedTask(task, safeHandler));
            queuedTotal++;
            if (!roundRobin.contains(tenant)) {
                roundRobin.addLast(tenant);
            }
            drain();
        }
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(tenants.size(), queuedTotal,
            tenants.values().stream().mapToInt(value -> value.active).sum());
    }

    private void drain() {
        int candidates = roundRobin.size();
        while (candidates-- > 0 && !roundRobin.isEmpty()) {
            String tenant = roundRobin.removeFirst();
            TenantQueue queue = tenants.get(tenant);
            if (queue == null) {
                continue;
            }
            if (queue.active >= maxConcurrentPerTenant || queue.waiting.isEmpty()) {
                if (!queue.waiting.isEmpty()) {
                    roundRobin.addLast(tenant);
                }
                continue;
            }
            QueuedTask task = queue.waiting.removeFirst();
            queuedTotal--;
            queue.active++;
            if (!queue.waiting.isEmpty()) {
                roundRobin.addLast(tenant);
            }
            try {
                delegate.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        completed(tenant);
                    }
                });
            } catch (RejectedExecutionException ex) {
                queue.active--;
                task.rejectionHandler().accept(ex);
                if (queue.active == 0 && queue.waiting.isEmpty()) {
                    tenants.remove(tenant);
                    roundRobin.remove(tenant);
                } else if (!queue.waiting.isEmpty() && !roundRobin.contains(tenant)) {
                    roundRobin.addLast(tenant);
                }
                candidates = roundRobin.size();
            }
            candidates = roundRobin.size();
        }
    }

    private synchronized void completed(String tenant) {
        TenantQueue queue = tenants.get(tenant);
        if (queue == null) {
            return;
        }
        queue.active = Math.max(0, queue.active - 1);
        if (!queue.waiting.isEmpty() && !roundRobin.contains(tenant)) {
            roundRobin.addLast(tenant);
        }
        if (queue.active == 0 && queue.waiting.isEmpty()) {
            tenants.remove(tenant);
            roundRobin.remove(tenant);
        }
        drain();
    }

    record Snapshot(int tenantCount, int queued, int active) {
    }

    private static final class TenantQueue {
        private final Deque<QueuedTask> waiting = new ArrayDeque<>();
        private int active;
    }

    private record QueuedTask(Runnable task, Consumer<RejectedExecutionException> rejectionHandler)
        implements Runnable {
        @Override
        public void run() {
            task.run();
        }
    }
}
