package com.chatchat.chat.dag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
    name = "runtime_dag_node_attempt",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dag_node_attempt_number",
        columnNames = {"tenant_id", "run_id", "node_id", "attempt_number"}
    ),
    indexes = {
        @Index(name = "idx_dag_node_attempt_run", columnList = "tenant_id, run_id, node_id"),
        @Index(name = "idx_dag_node_attempt_state", columnList = "tenant_id, state, updated_at"),
        @Index(name = "idx_dag_node_attempt_lease", columnList = "state, lease_expires_at")
    }
)
public class NodeAttemptEntity {

    @Id
    @Column(name = "attempt_id", length = 64, nullable = false)
    private String attemptId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "run_id", length = 128, nullable = false)
    private String runId;

    @Column(name = "execution_trace_id", length = 128)
    private String executionTraceId;

    @Column(name = "plan_version", length = 64)
    private String planVersion;

    @Column(name = "node_id", nullable = false)
    private Integer nodeId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "state", length = 24, nullable = false)
    private String state;

    @Column(name = "state_reason", length = 1000)
    private String stateReason;

    @Column(name = "execution_epoch", length = 128)
    private String executionEpoch;

    @Column(name = "prepared_at")
    private Instant preparedAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "node_definition_fingerprint", length = 64)
    private String nodeDefinitionFingerprint;

    @Column(name = "input_fingerprint", length = 64)
    private String inputFingerprint;

    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
