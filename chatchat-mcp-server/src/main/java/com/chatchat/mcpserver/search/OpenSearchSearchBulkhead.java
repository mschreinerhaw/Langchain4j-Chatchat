package com.chatchat.mcpserver.search;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class OpenSearchSearchBulkhead {

    private final String name;
    private final boolean enabled;
    private final int queueCapacity;
    private final int queueTimeoutMs;
    private final Semaphore permits;
    private final AtomicInteger waiting = new AtomicInteger();

    OpenSearchSearchBulkhead(String name, boolean enabled, LuceneSearchProperties.OpenSearch.Limit limit) {
        LuceneSearchProperties.OpenSearch.Limit effective = limit == null
            ? new LuceneSearchProperties.OpenSearch.Limit(1, 0, 0)
            : limit;
        this.name = name;
        this.enabled = enabled;
        this.queueCapacity = Math.max(0, effective.getQueueCapacity());
        this.queueTimeoutMs = Math.max(0, effective.getQueueTimeoutMs());
        this.permits = new Semaphore(Math.max(1, effective.getMaxRunning()), true);
    }

    <T> T execute(Supplier<T> operation) {
        if (!enabled) {
            return operation.get();
        }
        boolean acquired = permits.tryAcquire();
        if (!acquired) {
            acquired = awaitPermit();
        }
        if (!acquired) {
            throw new SearchRejectedException(name, "queue timeout or capacity exceeded");
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }

    int waitingCount() {
        return waiting.get();
    }

    private boolean awaitPermit() {
        int queued = waiting.incrementAndGet();
        if (queued > queueCapacity) {
            waiting.decrementAndGet();
            return false;
        }
        try {
            return queueTimeoutMs > 0 && permits.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            waiting.decrementAndGet();
        }
    }

    static final class SearchRejectedException extends RuntimeException {
        private final String bulkhead;

        SearchRejectedException(String bulkhead, String message) {
            super(message);
            this.bulkhead = bulkhead;
        }

        String bulkhead() {
            return bulkhead;
        }
    }
}
