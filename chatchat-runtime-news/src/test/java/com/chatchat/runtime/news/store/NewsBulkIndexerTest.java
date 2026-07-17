package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.model.NewsSourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class NewsBulkIndexerTest {
    @Test
    void rejectsWhenBoundedQueueIsFullAndWritesOnWorker() throws Exception {
        NewsRuntimeProperties runtime = new NewsRuntimeProperties();
        runtime.getBulk().setQueueCapacity(1);
        runtime.getBulk().setBatchSize(1);
        runtime.getBulk().setOfferTimeoutMillis(20);
        runtime.getBulk().setFlushIntervalMillis(10);
        runtime.getBulk().setMaxRetries(0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NewsDocumentStore store = new NewsDocumentStore() {
            @Override public void bulkIndex(List<NewsDocument> documents) throws Exception {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
            }
            @Override public List<NewsDocument> search(NewsSearchQuery query) { return List.of(); }
        };
        NewsIndexStateService states = mock(NewsIndexStateService.class);
        NewsBulkIndexer indexer = new NewsBulkIndexer(runtime, store, states);
        indexer.start();
        NewsDocument first = document("1");
        NewsDocument second = document("2");
        try {
            assertThat(indexer.submit(first)).isTrue();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(indexer.submit(second)).isTrue();
            assertThat(indexer.submit(document("3"))).isFalse();
            release.countDown();
            verify(states, timeout(1000)).indexed(List.of(first));
            verify(states, timeout(1000)).indexed(List.of(second));
        } finally {
            release.countDown();
            indexer.stop();
        }
    }

    private NewsDocument document(String id) {
        return new NewsDocument(id, 1L, "source", NewsSourceType.RSS, "title", "content", null, null,
            "https://example.com/" + id, Instant.now(), Instant.now(), "en", List.of(), List.of(), id,
            NewsAnalysisStatus.PENDING, Map.of());
    }
}
