package com.chatchat.chat.insight;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Immutable formula version plus explicit Agent Runtime applicability bindings. */
@Getter
@Setter
@Entity
@Table(name = "runtime_semantic_insight_contract",
    uniqueConstraints = @UniqueConstraint(name = "uk_semantic_contract_version",
        columnNames = {"tenant_id", "contract_key", "contract_version"}),
    indexes = {
        @Index(name = "idx_semantic_contract_active",
            columnList = "tenant_id,status,enabled,priority"),
        @Index(name = "idx_semantic_contract_binding",
            columnList = "tenant_id,agent_id,tool_name,dataset_key,task_type")
    })
public class SemanticInsightContractEntity {
    @Id
    @Column(name = "contract_id", length = 128, nullable = false)
    private String contractId;
    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;
    @Column(name = "contract_key", length = 128, nullable = false)
    private String contractKey;
    @Column(name = "contract_version", length = 64, nullable = false)
    private String contractVersion;
    @Column(length = 32, nullable = false)
    private String status = "DRAFT";
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "activation_mode", length = 32, nullable = false)
    private String activationMode = "EXPLICIT_ONLY";
    @Column(name = "agent_id", length = 128)
    private String agentId;
    @Column(name = "tool_name", length = 256)
    private String toolName;
    @Column(name = "dataset_key", length = 256)
    private String datasetKey;
    @Column(name = "task_type", length = 128)
    private String taskType;
    @Column(nullable = false)
    private int priority;
    @Lob
    @Column(name = "contract_json", nullable = false)
    private String contractJson;
    @Column(name = "effective_from")
    private Instant effectiveFrom;
    @Column(name = "effective_to")
    private Instant effectiveTo;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
}
