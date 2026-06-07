package com.chatchat.api.conversation;

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
    name = "chat_message_index",
    indexes = {
        @Index(name = "idx_chat_message_session_created", columnList = "session_id, created_at"),
        @Index(name = "idx_chat_message_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_chat_message_tenant_created", columnList = "tenant_id, created_at")
    }
)
public class ChatMessageIndexEntity {

    @Id
    @Column(name = "message_id", length = 64, nullable = false)
    private String messageId;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(length = 32, nullable = false)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "rocks_key", length = 512, nullable = false)
    private String rocksKey;

    @PrePersist
    public void onCreate() {
        if (messageId == null || messageId.isBlank()) {
            messageId = UUID.randomUUID().toString();
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        if (role == null || role.isBlank()) {
            role = "user";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
