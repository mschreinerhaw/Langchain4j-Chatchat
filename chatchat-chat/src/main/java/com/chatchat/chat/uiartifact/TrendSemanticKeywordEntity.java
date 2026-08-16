package com.chatchat.chat.uiartifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@IdClass(TrendSemanticKeywordKey.class)
@Table(name = "ui_trend_semantic_keyword")
public class TrendSemanticKeywordEntity {

    @Id
    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;

    @Id
    @Column(name = "keyword", length = 64, nullable = false)
    private String keyword;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
