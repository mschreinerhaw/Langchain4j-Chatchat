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

import java.time.Instant;

@Service
public class NewsIngestionService implements NewsItemSink {
    private final NewsNormalizer normalizer;
    private final NewsCollectRecordRepository repository;
    private final NewsBulkIndexer bulkIndexer;
    private final NewsAttachmentIngestionService attachmentIngestionService;

    public NewsIngestionService(NewsNormalizer normalizer, NewsCollectRecordRepository repository,
                                NewsBulkIndexer bulkIndexer, NewsAttachmentIngestionService attachmentIngestionService) {
        this.normalizer = normalizer;
        this.repository = repository;
        this.bulkIndexer = bulkIndexer;
        this.attachmentIngestionService = attachmentIngestionService;
    }

    @Override
    public synchronized NewsAcceptance accept(RawNewsItem rawItem) {
        try {
            NewsDocument document = normalizer.normalize(rawItem);
            String urlHash = normalizer.urlHash(document.sourceUrl());
            NewsCollectRecordEntity record = repository.findByUrlHash(urlHash).orElse(null);
            if (record != null && document.contentHash().equals(record.getContentHash())
                && record.getCollectStatus() != NewsCollectStatus.FAILED) {
                record.setCollectedAt(Instant.now());
                repository.save(record);
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
                return NewsAcceptance.REJECTED;
            }
            attachmentIngestionService.submit(document);
            return NewsAcceptance.ACCEPTED;
        } catch (IllegalArgumentException ex) {
            return NewsAcceptance.REJECTED;
        }
    }
}
