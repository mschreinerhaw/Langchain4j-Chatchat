package com.chatchat.chat.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
    name = "chat_session",
    indexes = {
        @Index(name = "idx_chat_session_user_updated", columnList = "user_id, updated_at"),
        @Index(name = "idx_chat_session_tenant_updated", columnList = "tenant_id, updated_at"),
        @Index(name = "idx_chat_session_title", columnList = "title")
    }
)
public class ChatSessionEntity {

    @Id
    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(length = 256, nullable = false)
    private String title;

    @Column(length = 32, nullable = false)
    private String status = "active";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        if (title == null || title.isBlank()) {
            title = "New Conversation";
        }
        if (status == null || status.isBlank()) {
            status = "active";
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
