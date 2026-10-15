package com.chatchat.mcpserver.python;

import jakarta.persistence.*;

/** Schema marker for the existing MCP database; authoritative updates use JDBC row locks. */
@Entity
@Table(name = "mcp_python_managed_operation")
class PythonManagedOperationRecord {
    @Id @Column(length = 64) String id;
    @Column(name = "tenant_id", length = 128, nullable = false) String tenantId;
    @Column(name = "owner_id", length = 128, nullable = false) String ownerId;
    @Column(name = "run_id", length = 128, nullable = false) String runId;
    @Column(name = "task_id", length = 128, nullable = false) String taskId;
    @Column(name = "operation_id", length = 128, nullable = false) String operationId;
    @Column(name = "attempt_number", nullable = false) int attemptNumber;
    @Column(name = "fencing_token", nullable = false) long fencingToken;
    @Column(name = "request_hash", length = 64, nullable = false) String requestHash;
    @Column(name = "environment_id", length = 64, nullable = false) String environmentId;
    @Column(name = "environment_version", nullable = false) int environmentVersion;
    @Column(name = "image_digest", length = 71, nullable = false) String imageDigest;
    @Column(name = "container_name", length = 64, nullable = false) String containerName;
    @Column(name = "workspace_path", length = 2000, nullable = false) String workspacePath;
    @Column(name = "code_hash", length = 64, nullable = false) String codeHash;
    @Column(name = "deadline_at", nullable = false) java.time.Instant deadlineAt;
    @Column(name = "timeout_seconds", nullable = false) int timeoutSeconds;
    @Column(name = "container_started_at") java.time.Instant containerStartedAt;
    @Column(name = "budget_reservation_ref", length = 256, nullable = false) String budgetReservationRef;
    @Column(name = "manifest_hash", length = 64) String manifestHash;
    @Column(length = 32, nullable = false) String status;
    @Column(name = "cancel_requested", nullable = false) boolean cancelRequested;
    @Column(name = "executor_stopped", nullable = false) boolean executorStopped;
    @Column(name = "exit_code") Integer exitCode;
    @Column(length = 2000) String failure;
    @Column(name = "cleanup_failed", nullable = false) boolean cleanupFailed;
    @Column(nullable = false) long revision;
    @Column(name = "created_at", nullable = false) java.time.Instant createdAt;
    @Column(name = "updated_at", nullable = false) java.time.Instant updatedAt;
}
