package com.chatchat.chat.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
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
    name = "conversation_summary",
    indexes = {
        @Index(name = "idx_conversation_summary_session_created", columnList = "session_id, created_at"),
        @Index(name = "idx_conversation_summary_end", columnList = "message_end_id")
    }
)
public class ConversationSummaryEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Lob
    @Column(name = "summary", nullable = false, columnDefinition = "LONGTEXT")
    private String summary;

    @Column(name = "message_start_id", length = 64, nullable = false)
    private String messageStartId;

    @Column(name = "message_end_id", length = 64, nullable = false)
    private String messageEndId;

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
