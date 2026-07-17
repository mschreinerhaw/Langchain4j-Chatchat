package com.chatchat.runtime.news.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "news_source_rule", uniqueConstraints = @UniqueConstraint(name = "uk_news_source_rule", columnNames = "source_id"))
public class NewsSourceRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "list_selector", length = 1000)
    private String listSelector;
    @Column(name = "link_selector", length = 1000)
    private String linkSelector;
    @Column(name = "title_selector", length = 1000)
    private String titleSelector;
    @Column(name = "content_selector", length = 1000)
    private String contentSelector;
    @Column(name = "author_selector", length = 1000)
    private String authorSelector;
    @Column(name = "publish_time_selector", length = 1000)
    private String publishTimeSelector;
    @Column(name = "url_pattern", length = 1000)
    private String urlPattern;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
