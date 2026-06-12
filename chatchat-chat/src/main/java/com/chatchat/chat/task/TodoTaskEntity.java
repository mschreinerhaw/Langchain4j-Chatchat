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
    name = "todo_task",
    indexes = {
        @Index(name = "idx_todo_task_tenant_user_status", columnList = "tenant_id, user_id, status"),
        @Index(name = "idx_todo_task_task_type", columnList = "tenant_id, task_id, todo_type")
    }
)
public class TodoTaskEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId = "anonymous";

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(length = 300, nullable = false)
    private String title;

    @Column(name = "todo_type", length = 48, nullable = false)
    private String todoType;

    @Column(length = 32, nullable = false)
    private String status = "PENDING";

    @Column(length = 24, nullable = false)
    private String priority = "MEDIUM";

    @Column(length = 128)
    private String source;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (status == null || status.isBlank()) {
            status = "PENDING";
        }
        if (priority == null || priority.isBlank()) {
            priority = "MEDIUM";
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
