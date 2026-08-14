package com.chatchat.chat.activity;

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
    name = "personal_todo",
    indexes = {
        @Index(name = "idx_personal_todo_user_status", columnList = "tenant_id, user_id, completed, updated_at")
    }
)
public class PersonalTodoEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(length = 300, nullable = false)
    private String title;

    @Column(length = 2000)
    private String notes;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(nullable = false)
    private boolean completed;

    @Column(nullable = false)
    private boolean important;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
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
