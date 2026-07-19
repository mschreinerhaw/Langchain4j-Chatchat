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
import org.springframework.data.domain.PageRequest;

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

    @Transactional
    public List<NewsAnalysisTaskEntity> claimAnalysisTasks(int batchSize) {
        List<NewsAnalysisTaskEntity> tasks = analysisTaskRepository.findNextByStatus(
            NewsAnalysisStatus.PENDING, PageRequest.of(0, Math.max(1, batchSize)));
        Instant now = Instant.now();
        for (NewsAnalysisTaskEntity task : tasks) {
            task.setStatus(NewsAnalysisStatus.PROCESSING);
            task.setUpdatedAt(now);
            task.setErrorMessage(null);
            recordRepository.findByDocumentId(task.getDocumentId()).ifPresent(record -> {
                record.setAnalysisStatus(NewsAnalysisStatus.PROCESSING);
                recordRepository.save(record);
            });
        }
        return analysisTaskRepository.saveAll(tasks);
    }

    @Transactional
    public void analysisCompleted(Long taskId, String documentId) {
        analysisTaskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(NewsAnalysisStatus.COMPLETED);
            task.setUpdatedAt(Instant.now());
            task.setErrorMessage(null);
            analysisTaskRepository.save(task);
        });
        recordRepository.findByDocumentId(documentId).ifPresent(record -> {
            record.setAnalysisStatus(NewsAnalysisStatus.COMPLETED);
            record.setErrorMessage(null);
            recordRepository.save(record);
        });
    }

    @Transactional
    public void analysisFailed(Long taskId, String documentId, String message) {
        String error = message == null || message.isBlank() ? "Unknown news analysis error" : message;
        if (error.length() > 4000) error = error.substring(0, 4000);
        final String safeError = error;
        analysisTaskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(NewsAnalysisStatus.FAILED);
            task.setUpdatedAt(Instant.now());
            task.setErrorMessage(safeError);
            analysisTaskRepository.save(task);
        });
        recordRepository.findByDocumentId(documentId).ifPresent(record -> {
            record.setAnalysisStatus(NewsAnalysisStatus.FAILED);
            record.setErrorMessage(safeError);
            recordRepository.save(record);
        });
    }
}
