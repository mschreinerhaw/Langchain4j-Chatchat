package com.chatchat.chat.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agent_runtime_run", indexes = {
    @Index(name = "idx_runtime_run_tenant_updated", columnList = "tenant_id, updated_at"),
    @Index(name = "idx_runtime_run_status_updated", columnList = "run_status, updated_at"),
    @Index(name = "idx_runtime_run_execution", columnList = "tenant_id, execution_id, attempt_number")
})
public class AgentRuntimeRunEntity {

    @Id
    @Column(name = "run_id", length = 64, nullable = false)
    private String runId;

    @Column(name = "execution_id", length = 64)
    private String executionId;

    @Column(name = "attempt_number")
    private Integer attemptNumber;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "run_status", length = 32, nullable = false)
    private String status;

    @Column(name = "run_json", columnDefinition = "LONGTEXT", nullable = false)
    private String runJson;

    @Column(name = "started_at", nullable = false)
    private Long startedAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision;
}
