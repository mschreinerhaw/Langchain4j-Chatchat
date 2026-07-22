package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsCollectStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.chatchat.runtime.news.normalize.NewsNormalizer;
import com.chatchat.runtime.news.source.NewsCollectRecordEntity;
import com.chatchat.runtime.news.source.NewsCollectRecordRepository;
import com.chatchat.runtime.news.store.NewsBulkIndexer;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@Service
public class NewsIngestionService implements NewsItemSink {
    private static final Logger log = LoggerFactory.getLogger(NewsIngestionService.class);
    private final NewsNormalizer normalizer;
    private final NewsCollectRecordRepository repository;
    private final NewsBulkIndexer bulkIndexer;
    private final NewsAttachmentIngestionService attachmentIngestionService;
    private final McpMarketIngestionClient marketRuntimeIngestionClient;

    public NewsIngestionService(NewsNormalizer normalizer, NewsCollectRecordRepository repository,
                                NewsBulkIndexer bulkIndexer, NewsAttachmentIngestionService attachmentIngestionService,
                                McpMarketIngestionClient marketRuntimeIngestionClient) {
        this.normalizer = normalizer;
        this.repository = repository;
        this.bulkIndexer = bulkIndexer;
        this.attachmentIngestionService = attachmentIngestionService;
        this.marketRuntimeIngestionClient = marketRuntimeIngestionClient;
    }

    @Override
    public synchronized NewsAcceptance accept(RawNewsItem rawItem) {
        try {
            if (marketRuntimeIngestionClient.accepts(rawItem)) {
                marketRuntimeIngestionClient.accept(rawItem);
                log.info("market_observation_forwarded sourceId={} url={}",
                    rawItem.source() == null ? null : rawItem.source().id(), rawItem.sourceUrl());
                return NewsAcceptance.ACCEPTED;
            }
            NewsDocument document = normalizer.normalize(rawItem);
            String urlHash = normalizer.urlHash(document.sourceUrl());
            NewsCollectRecordEntity record = repository.findByUrlHash(urlHash).orElse(null);
            if (record != null && document.contentHash().equals(record.getContentHash())
                && record.getCollectStatus() != NewsCollectStatus.FAILED) {
                record.setCollectedAt(Instant.now());
                repository.save(record);
                log.debug("news_item_duplicate sourceId={} documentId={} url={}",
                    document.sourceId(), document.documentId(), document.sourceUrl());
                return NewsAcceptance.DUPLICATE;
            }
            if (record == null) record = new NewsCollectRecordEntity();
            record.setSourceId(document.sourceId());
            record.setSourceUrl(document.sourceUrl());
            record.setUrlHash(urlHash);
            record.setContentHash(document.contentHash());
            record.setPublishTime(document.publishTime());
            record.setCollectStatus(NewsCollectStatus.QUEUED);
            record.setAnalysisStatus(NewsAnalysisStatus.PENDING);
            record.setDocumentId(document.documentId());
            record.setCollectedAt(Instant.now());
            record.setErrorMessage(null);
            record = repository.saveAndFlush(record);
            if (!bulkIndexer.submit(document)) {
                record.setCollectStatus(NewsCollectStatus.FAILED);
                record.setErrorMessage("Bounded OpenSearch Bulk queue is full");
                repository.save(record);
                log.warn("news_item_rejected reason=bulk_queue_full sourceId={} documentId={} url={}",
                    document.sourceId(), document.documentId(), document.sourceUrl());
                return NewsAcceptance.REJECTED;
            }
            log.info("news_item_queued sourceId={} sourceName={} documentId={} url={} bulkQueueDepth={}",
                document.sourceId(), document.sourceName(), document.documentId(), document.sourceUrl(), bulkIndexer.queued());
            attachmentIngestionService.submit(document);
            return NewsAcceptance.ACCEPTED;
        } catch (IllegalArgumentException ex) {
            log.warn("news_item_rejected reason=normalization sourceId={} url={} error={}",
                rawItem == null || rawItem.source() == null ? null : rawItem.source().id(),
                rawItem == null ? null : rawItem.sourceUrl(), ex.getMessage());
            return NewsAcceptance.REJECTED;
        }
    }
}
