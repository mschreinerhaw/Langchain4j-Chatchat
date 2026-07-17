package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.analysis.NewsAnalysisTaskEntity;
import com.chatchat.runtime.news.analysis.NewsAnalysisTaskRepository;
import com.chatchat.runtime.news.model.NewsCollectStatus;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.source.NewsCollectRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NewsIndexStateService {
    private final NewsCollectRecordRepository recordRepository;
    private final NewsAnalysisTaskRepository analysisTaskRepository;

    public NewsIndexStateService(NewsCollectRecordRepository recordRepository,
                                 NewsAnalysisTaskRepository analysisTaskRepository) {
        this.recordRepository = recordRepository;
        this.analysisTaskRepository = analysisTaskRepository;
    }

    @Transactional
    public void indexed(List<NewsDocument> documents) {
        for (NewsDocument document : documents) {
            recordRepository.findByDocumentId(document.documentId()).ifPresent(record -> {
                record.setCollectStatus(NewsCollectStatus.INDEXED);
                record.setErrorMessage(null);
                recordRepository.save(record);
            });
            if ("attachment_chunk".equals(document.metadata().get("documentKind"))) continue;
            NewsAnalysisTaskEntity task = analysisTaskRepository.findByDocumentId(document.documentId())
                .orElseGet(NewsAnalysisTaskEntity::new);
            if (task.getId() == null) {
                task.setDocumentId(document.documentId());
                task.setSourceId(document.sourceId());
            }
            task.setStatus(NewsAnalysisStatus.PENDING);
            task.setUpdatedAt(Instant.now());
            task.setErrorMessage(null);
            analysisTaskRepository.save(task);
        }
    }

    @Transactional
    public void failed(List<NewsDocument> documents, String message) {
        String safeMessage = message == null ? "Unknown OpenSearch Bulk error" : message;
        if (safeMessage.length() > 4000) safeMessage = safeMessage.substring(0, 4000);
        final String error = safeMessage;
        for (NewsDocument document : documents) {
            recordRepository.findByDocumentId(document.documentId()).ifPresent(record -> {
                record.setCollectStatus(NewsCollectStatus.FAILED);
                record.setErrorMessage(error);
                record.setCollectedAt(Instant.now());
                recordRepository.save(record);
            });
        }
    }
}
