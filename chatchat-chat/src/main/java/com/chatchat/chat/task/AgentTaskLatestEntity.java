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
    name = "agent_task_latest",
    indexes = {
        @Index(name = "idx_agent_task_tenant_created", columnList = "tenant_id, create_time"),
        @Index(name = "idx_agent_task_session_created", columnList = "tenant_id, session_id, create_time"),
        @Index(name = "idx_agent_task_status_updated", columnList = "status, update_time")
    }
)
public class AgentTaskLatestEntity {

    @Id
    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId = "anonymous";

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(length = 32, nullable = false)
    private String status = "PENDING";

    @Column(length = 4000)
    private String question;

    @Column(name = "answer_summary", length = 4000)
    private String answerSummary;

    @Column(name = "error_message", length = 4000)
    private String errorMessage;

    @Column(name = "create_time", nullable = false)
    private Instant createTime;

    @Column(name = "update_time", nullable = false)
    private Instant updateTime;

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
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        if (status == null || status.isBlank()) {
            status = "PENDING";
        }
        Instant now = Instant.now();
        if (createTime == null) {
            createTime = now;
        }
        updateTime = now;
    }

    @PreUpdate
    public void onUpdate() {
        updateTime = Instant.now();
    }
}
