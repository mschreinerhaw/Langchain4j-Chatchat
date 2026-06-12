package com.chatchat.chat.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
    name = "user_favorite",
    indexes = {
        @Index(name = "idx_user_favorite_user_created", columnList = "tenant_id, user_id, created_at"),
        @Index(name = "idx_user_favorite_user_category", columnList = "tenant_id, user_id, category, created_at"),
        @Index(name = "idx_user_favorite_target", columnList = "tenant_id, user_id, target_type, target_id")
    }
)
public class UserFavoriteEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "target_type", length = 32, nullable = false)
    private String targetType;

    @Column(name = "target_id", length = 128, nullable = false)
    private String targetId;

    @Column(length = 80)
    private String category;

    @Column(length = 300, nullable = false)
    private String title;

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
        if (category == null || category.isBlank()) {
            category = "默认";
        }
    }
}
