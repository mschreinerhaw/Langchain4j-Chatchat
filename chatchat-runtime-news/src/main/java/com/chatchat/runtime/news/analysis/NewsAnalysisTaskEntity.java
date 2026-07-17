package com.chatchat.runtime.news.analysis;

import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "news_analysis_task", uniqueConstraints = @UniqueConstraint(name = "uk_news_analysis_document", columnNames = "document_id"))
public class NewsAnalysisTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "document_id", nullable = false, length = 128)
    private String documentId;
    @Column(name = "source_id", nullable = false)
    private Long sourceId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NewsAnalysisStatus status = NewsAnalysisStatus.PENDING;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
    @Column(name = "error_message", length = 4000)
    private String errorMessage;
}
