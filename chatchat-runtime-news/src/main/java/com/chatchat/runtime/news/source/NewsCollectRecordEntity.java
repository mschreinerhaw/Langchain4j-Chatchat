package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsCollectStatus;
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
@Table(name = "news_collect_record", uniqueConstraints = @UniqueConstraint(name = "uk_news_url_hash", columnNames = "url_hash"))
public class NewsCollectRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "source_id", nullable = false)
    private Long sourceId;
    @Column(name = "source_url", nullable = false, length = 2000)
    private String sourceUrl;
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;
    @Column(name = "content_hash", length = 64)
    private String contentHash;
    @Column(name = "publish_time")
    private Instant publishTime;
    @Enumerated(EnumType.STRING)
    @Column(name = "collect_status", nullable = false, length = 32)
    private NewsCollectStatus collectStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 32)
    private NewsAnalysisStatus analysisStatus;
    @Column(name = "document_id", length = 128)
    private String documentId;
    @Column(name = "collected_at")
    private Instant collectedAt;
    @Column(name = "error_message", length = 4000)
    private String errorMessage;
}
