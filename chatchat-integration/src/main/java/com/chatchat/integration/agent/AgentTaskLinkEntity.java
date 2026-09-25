package com.chatchat.integration.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_a2a_task_link", indexes = {
    @Index(name = "idx_agent_a2a_task_tenant", columnList = "tenant_id, agent_id"),
    @Index(name = "idx_agent_a2a_task_expiry", columnList = "created_at_epoch_ms")
})
public class AgentTaskLinkEntity {
    @Id @Column(name = "execution_id", length = 128, nullable = false)
    private String executionId;
    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;
    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;
    @Column(name = "task_id", length = 256, nullable = false)
    private String taskId;
    @Column(name = "context_id", length = 256)
    private String contextId;
    @Column(name = "created_at_epoch_ms", nullable = false)
    private long createdAtEpochMs;

    protected AgentTaskLinkEntity() { }

    AgentTaskLinkEntity(AgentTaskLinkStore.TaskLink link) {
        this.executionId = link.executionId();
        this.tenantId = link.tenantId();
        this.agentId = link.agentId();
        this.taskId = link.taskId();
        this.contextId = link.contextId();
        this.createdAtEpochMs = link.createdAtEpochMs();
    }

    AgentTaskLinkStore.TaskLink toLink() {
        return new AgentTaskLinkStore.TaskLink(executionId, tenantId, agentId,
            taskId, contextId, createdAtEpochMs);
    }
}
