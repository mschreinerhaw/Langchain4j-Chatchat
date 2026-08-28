package com.chatchat.chat.task.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agent_execution_event", indexes = {
    @Index(name = "idx_execution_event_task_seq", columnList = "tenant_id, task_id, sequence_number"),
    @Index(name = "idx_execution_event_session_time", columnList = "tenant_id, session_id, created_at"),
    @Index(name = "idx_execution_event_run_time", columnList = "run_id, created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_execution_event_task_sequence",
    columnNames = {"task_id", "sequence_number"}))
public class DatabaseAgentEventEntity {

    @Id
    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "execution_id", length = 64)
    private String executionId;

    @Column(name = "attempt_id", length = 64)
    private String attemptId;

    @Column(name = "event_scope", length = 16)
    private String eventScope = "TASK";

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "parent_event_id", length = 64)
    private String parentEventId;

    @Column(name = "sequence_number", nullable = false)
    private Long sequence;

    @Column(name = "tool_name", length = 256)
    private String toolName;

    @Column(name = "event_type", length = 64, nullable = false)
    private String type;

    @Column(name = "execution_status", length = 32)
    private String status;

    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
