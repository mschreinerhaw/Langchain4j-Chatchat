package com.chatchat.mcpserver.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchSearchBulkheadTest {

    @Test
    void rejectsImmediatelyWhenRunningAndQueueCapacityAreExhausted() throws Exception {
        OpenSearchSearchBulkhead bulkhead = new OpenSearchSearchBulkhead(
            "vector", true, new LuceneSearchProperties.OpenSearch.Limit(1, 0, 500));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> running = executor.submit(() -> bulkhead.execute(() -> {
                entered.countDown();
                await(release);
                return "done";
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> bulkhead.execute(() -> "rejected"))
                .isInstanceOf(OpenSearchSearchBulkhead.SearchRejectedException.class)
                .hasMessageContaining("capacity exceeded");

            release.countDown();
            assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo("done");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void boundsQueueWaitByConfiguredTimeout() throws Exception {
        OpenSearchSearchBulkhead bulkhead = new OpenSearchSearchBulkhead(
            "lexical", true, new LuceneSearchProperties.OpenSearch.Limit(1, 1, 60));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> bulkhead.execute(() -> {
                entered.countDown();
                await(release);
                return null;
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> bulkhead.execute(() -> null))
                .isInstanceOf(OpenSearchSearchBulkhead.SearchRejectedException.class);

            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertThat(waitedMs).isBetween(40L, 500L);
            assertThat(bulkhead.waitingCount()).isZero();
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exposesSafeDefaultsForLexicalAndVectorSearch() {
        LuceneSearchProperties.OpenSearch.SearchConcurrency defaults =
            new LuceneSearchProperties.OpenSearch.SearchConcurrency();

        assertThat(defaults.getLexical().getMaxRunning()).isEqualTo(12);
        assertThat(defaults.getLexical().getQueueCapacity()).isEqualTo(30);
        assertThat(defaults.getVector().getMaxRunning()).isEqualTo(4);
        assertThat(defaults.getRequestTimeoutMs()).isEqualTo(8000);
        assertThat(defaults.getRetry429Attempts()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
