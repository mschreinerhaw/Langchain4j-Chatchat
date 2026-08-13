package com.chatchat.chat.uiartifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "ui_trend_semantic_config")
public class TrendSemanticConfigEntity {

    @Id
    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;

    @Column(name = "keywords_json", columnDefinition = "TEXT", nullable = false)
    private String keywordsJson;

    @Column(name = "up_color", length = 16, nullable = false)
    private String upColor;

    @Column(name = "down_color", length = 16, nullable = false)
    private String downColor;

    @Column(name = "neutral_color", length = 16, nullable = false)
    private String neutralColor;

    @Column(nullable = false)
    private long revision = 1;

    @Column(name = "ruleset_version", nullable = false, columnDefinition = "integer default 1")
    private int rulesetVersion = 1;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = Instant.now();
    }
}
