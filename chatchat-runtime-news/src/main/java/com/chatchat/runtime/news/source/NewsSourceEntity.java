package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.model.NewsSourceType;
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
@Table(name = "news_source", uniqueConstraints = @UniqueConstraint(name = "uk_news_source_code", columnNames = "source_code"))
public class NewsSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capability_id", nullable = false)
    private Long capabilityId;

    @Column(name = "source_code", nullable = false, length = 64)
    private String sourceCode;

    @Column(name = "source_name", nullable = false, length = 128)
    private String sourceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private NewsSourceType sourceType;

    @Column(name = "entry_url", nullable = false, length = 2000)
    private String entryUrl;

    @Column(name = "allowed_domain", length = 255)
    private String allowedDomain;

    @Column(name = "schedule_cron", length = 128)
    private String scheduleCron;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "configuration_json", length = 8000)
    private String configurationJson;

    @Column(name = "last_cursor", length = 2000)
    private String lastCursor;

    @Column(name = "last_collected_at")
    private Instant lastCollectedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
