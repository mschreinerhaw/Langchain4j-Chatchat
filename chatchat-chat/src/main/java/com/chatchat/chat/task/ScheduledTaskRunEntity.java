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
    name = "scheduled_task_run",
    indexes = {
        @Index(name = "idx_scheduled_task_run_schedule_fire", columnList = "scheduled_task_id, fire_time"),
        @Index(name = "idx_scheduled_task_run_tenant_agent_fire", columnList = "tenant_id, agent_id, fire_time"),
        @Index(name = "idx_scheduled_task_run_task", columnList = "task_id"),
        @Index(name = "idx_scheduled_task_run_status_updated", columnList = "status, updated_at")
    }
)
public class ScheduledTaskRunEntity {

    @Id
    @Column(name = "run_id", length = 64, nullable = false)
    private String runId;

    @Column(name = "scheduled_task_id", length = 64, nullable = false)
    private String scheduledTaskId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId = "anonymous";

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(length = 32, nullable = false)
    private String status = "SCHEDULED";

    @Column(length = 4000, nullable = false)
    private String question;

    @Column(name = "fire_time", nullable = false)
    private Instant fireTime;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "answer_summary", length = 4000)
    private String answerSummary;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "manual_run", nullable = false)
    private Boolean manualRun = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (runId == null || runId.isBlank()) {
            runId = UUID.randomUUID().toString();
        }
        if (scheduledTaskId == null || scheduledTaskId.isBlank()) {
            throw new IllegalStateException("Scheduled task ID cannot be empty");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("Tenant ID cannot be empty");
        }
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        if (status == null || status.isBlank()) {
            status = "SCHEDULED";
        }
        if (question == null || question.isBlank()) {
            throw new IllegalStateException("Scheduled task run question cannot be empty");
        }
        if (fireTime == null) {
            fireTime = Instant.now();
        }
        if (manualRun == null) {
            manualRun = false;
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
