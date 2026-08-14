package com.chatchat.chat.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
    name = "user_favorite_category",
    indexes = {
        @Index(name = "idx_user_favorite_category_user", columnList = "tenant_id, user_id, created_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_favorite_category_name", columnNames = {"tenant_id", "user_id", "category_name"})
    }
)
public class UserFavoriteCategoryEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "category_name", length = 80, nullable = false)
    private String categoryName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
