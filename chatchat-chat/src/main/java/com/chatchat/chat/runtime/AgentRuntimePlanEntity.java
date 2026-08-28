package com.chatchat.chat.runtime;

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
@Table(name = "agent_runtime_plan", indexes = {
    @Index(name = "idx_runtime_plan_task", columnList = "tenant_id, task_id, plan_version")
}, uniqueConstraints = @UniqueConstraint(name = "uk_runtime_plan_version",
    columnNames = {"tenant_id", "task_id", "plan_version"}))
public class AgentRuntimePlanEntity {

    @Id
    @Column(name = "record_id", length = 256, nullable = false)
    private String recordId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "plan_id", length = 256, nullable = false)
    private String planId;

    @Column(name = "plan_version", nullable = false)
    private Integer version;

    @Column(name = "plan_json", columnDefinition = "LONGTEXT", nullable = false)
    private String planJson;

    @Column(name = "dag_json", columnDefinition = "LONGTEXT", nullable = false)
    private String dagJson;

    @Column(name = "plan_status", length = 32, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
