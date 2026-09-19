package com.chatchat.knowledgebase.search.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "kb_document_business_category",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_kb_document_category_code", columnNames = "code"),
        @UniqueConstraint(name = "uk_kb_document_category_name", columnNames = "name")
    },
    indexes = @Index(name = "idx_kb_document_category_sort", columnList = "sort_order,name"))
public class DocumentBusinessCategoryEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String code;

    @Column(length = 120, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean builtin;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (code == null || code.isBlank()) {
            code = "custom-" + id;
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
