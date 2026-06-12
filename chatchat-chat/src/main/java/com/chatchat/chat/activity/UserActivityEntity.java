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
    name = "user_activity",
    indexes = {
        @Index(name = "idx_user_activity_user_target", columnList = "tenant_id, user_id, target_type, created_at"),
        @Index(name = "idx_user_activity_user_action", columnList = "tenant_id, user_id, target_type, action_type, created_at")
    }
)
public class UserActivityEntity {

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

    @Column(name = "action_type", length = 32, nullable = false)
    private String actionType;

    @Column(length = 300)
    private String title;

    @Column(length = 1000)
    private String summary;

    @Column(name = "extra_json", columnDefinition = "TEXT")
    private String extraJson;

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
