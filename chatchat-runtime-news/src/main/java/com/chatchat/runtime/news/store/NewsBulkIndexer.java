package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsDocument;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded collector-to-OpenSearch boundary. Only this worker performs Bulk writes. */
@Component
public class NewsBulkIndexer {
    private static final Logger log = LoggerFactory.getLogger(NewsBulkIndexer.class);

    private final NewsRuntimeProperties.Bulk properties;
    private final NewsDocumentStore store;
    private final NewsIndexStateService stateService;
    private final ArrayBlockingQueue<NewsDocument> queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public NewsBulkIndexer(NewsRuntimeProperties runtimeProperties, NewsDocumentStore store,
                           NewsIndexStateService stateService) {
        this.properties = runtimeProperties.getBulk();
        this.store = store;
        this.stateService = stateService;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this::run, "runtime-news-bulk-indexer");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean submit(NewsDocument document) {
        try {
            return queue.offer(document, Math.max(0, properties.getOfferTimeoutMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public int queued() {
        return queue.size();
    }

    private void run() {
        while (running.get() || !queue.isEmpty()) {
            try {
                NewsDocument first = queue.poll(Math.max(1, properties.getFlushIntervalMillis()), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<NewsDocument> batch = new ArrayList<>(Math.max(1, properties.getBatchSize()));
                batch.add(first);
                queue.drainTo(batch, Math.max(0, properties.getBatchSize() - 1));
                indexWithRetry(batch);
            } catch (InterruptedException ex) {
                if (running.get()) log.warn("News Bulk worker was interrupted", ex);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.error("Unexpected news Bulk worker error", ex);
            }
        }
    }

    private void indexWithRetry(List<NewsDocument> batch) {
        Exception last = null;
        String sourceIds = batch.stream().map(NewsDocument::sourceId).distinct().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                long startedAt = System.currentTimeMillis();
                log.info("news_bulk_started documents={} sourceIds={} attempt={} queueRemaining={}",
                    batch.size(), sourceIds, attempt + 1, queue.size());
                store.bulkIndex(batch);
                stateService.indexed(batch);
                log.info("news_bulk_completed documents={} sourceIds={} attempt={} durationMs={} queueRemaining={}",
                    batch.size(), sourceIds, attempt + 1, System.currentTimeMillis() - startedAt, queue.size());
                return;
            } catch (Exception ex) {
                last = ex;
                if (attempt < properties.getMaxRetries()) {
                    log.warn("news_bulk_retry documents={} sourceIds={} attempt={} error={}",
                        batch.size(), sourceIds, attempt + 1, ex.getMessage());
                    backoff(attempt);
                }
            }
        }
        stateService.failed(batch, last == null ? null : last.getMessage());
        log.error("news_bulk_failed documents={} sourceIds={} attempts={} queueRemaining={}",
            batch.size(), sourceIds, properties.getMaxRetries() + 1, queue.size(), last);
    }

    private void backoff(int attempt) {
        long delay = attempt == 0 ? properties.getFirstBackoffMillis() : properties.getSecondBackoffMillis();
        try {
            Thread.sleep(Math.max(0, delay));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
