package com.chatchat.chat.task;

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
    name = "task_confirm",
    indexes = {
        @Index(name = "idx_task_confirm_task_created", columnList = "task_id, created_at"),
        @Index(name = "idx_task_confirm_status_expired", columnList = "status, expired_at")
    }
)
public class TaskConfirmEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "tool_name", length = 200)
    private String toolName;

    @Column(name = "confirm_message", length = 2000)
    private String confirmMessage;

    @Column(length = 32, nullable = false)
    private String status = "WAITING_CONFIRM";

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    @Column(name = "confirmed_by", length = 64)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("Task ID cannot be empty");
        }
        if (status == null || status.isBlank()) {
            status = "WAITING_CONFIRM";
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
