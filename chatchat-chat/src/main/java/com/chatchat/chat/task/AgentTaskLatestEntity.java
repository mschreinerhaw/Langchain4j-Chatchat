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

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(name = "request_payload_json", columnDefinition = "TEXT")
    private String requestPayloadJson;

    @Column(name = "answer_summary", columnDefinition = "LONGTEXT")
    private String answerSummary;

    /**
     * Immutable full answer and readable references used by notification delivery.
     * Kept separately from transient event storage so scheduled notifications never
     * have to fall back to a shortened task-list summary.
     */
    @Column(name = "final_notification_json", columnDefinition = "LONGTEXT")
    private String finalNotificationJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "feedback_useful")
    private Boolean feedbackUseful;

    @Column(name = "feedback_adopted")
    private Boolean feedbackAdopted;

    @Column(name = "feedback_resolved")
    private Boolean feedbackResolved;

    @Column(name = "feedback_comment", length = 1000)
    private String feedbackComment;

    @Column(name = "feedback_reason_category", length = 64)
    private String feedbackReasonCategory;

    @Column(name = "feedback_time")
    private Instant feedbackTime;

    @Column(name = "create_time", nullable = false)
    private Instant createTime;

    @Column(name = "update_time", nullable = false)
    private Instant updateTime;

    /**
     * Performs the on create operation.
     */
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

    /**
     * Performs the on update operation.
     */
    @PreUpdate
    public void onUpdate() {
        updateTime = Instant.now();
    }
}
