package com.chatchat.mcpserver.python;

import jakarta.persistence.*;

@Entity
@Table(name = "mcp_python_managed_fence")
class PythonManagedFenceRecord {
    @Id @Column(length = 64) String id;
    @Column(name = "tenant_id", length = 128, nullable = false) String tenantId;
    @Column(name = "owner_id", length = 128, nullable = false) String ownerId;
    @Column(name = "run_id", length = 128, nullable = false) String runId;
    @Column(name = "task_id", length = 128, nullable = false) String taskId;
    @Column(name = "fencing_token", nullable = false) long fencingToken;
}
