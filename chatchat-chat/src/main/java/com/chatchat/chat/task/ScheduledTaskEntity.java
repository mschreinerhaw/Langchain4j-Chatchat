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
    name = "scheduled_task",
    indexes = {
        @Index(name = "idx_scheduled_task_tenant_created", columnList = "tenant_id, created_at"),
        @Index(name = "idx_scheduled_task_status_next", columnList = "status, next_fire_time"),
        @Index(name = "idx_scheduled_task_status_expired", columnList = "status, expired_at"),
        @Index(name = "idx_scheduled_task_last_task", columnList = "last_task_id")
    }
)
public class ScheduledTaskEntity {

    @Id
    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId = "anonymous";

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(name = "trigger_type", length = 24, nullable = false)
    private String triggerType;

    @Column(name = "cron_expr", length = 120)
    private String cronExpr;

    @Column(name = "interval_seconds")
    private Long intervalSeconds;

    @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Column(length = 4000, nullable = false)
    private String question;

    @Column(name = "notify_enabled", nullable = false)
    private Boolean notifyEnabled = false;

    @Column(name = "trading_day_only", nullable = false)
    private Boolean tradingDayOnly = false;

    @Column(length = 32, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "next_fire_time")
    private Instant nextFireTime;

    @Column(name = "last_fire_time")
    private Instant lastFireTime;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "max_retries")
    private Integer maxRetries = 0;

    @Column(name = "retry_delay_seconds")
    private Long retryDelaySeconds = 60L;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "last_task_id", length = 64)
    private String lastTaskId;

    @Column(name = "last_task_status", length = 32)
    private String lastTaskStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("Tenant ID cannot be empty");
        }
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        if (triggerType == null || triggerType.isBlank()) {
            throw new IllegalStateException("Trigger type cannot be empty");
        }
        if (name == null || name.isBlank()) {
            name = agentId == null || agentId.isBlank() ? "Agent 定时任务" : agentId + " 定时任务";
        }
        if (question == null || question.isBlank()) {
            throw new IllegalStateException("Scheduled task question cannot be empty");
        }
        if (notifyEnabled == null) {
            notifyEnabled = false;
        }
        if (tradingDayOnly == null) {
            tradingDayOnly = false;
        }
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetries == null) {
            maxRetries = 0;
        }
        if (retryDelaySeconds == null) {
            retryDelaySeconds = 60L;
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
