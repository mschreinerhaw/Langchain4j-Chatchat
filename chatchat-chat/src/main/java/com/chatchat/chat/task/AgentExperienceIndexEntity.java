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
    name = "experience_index",
    indexes = {
        @Index(name = "idx_experience_index_lookup", columnList = "tenant_id, agent_id, scenario, intent_type"),
        @Index(name = "idx_experience_index_score", columnList = "tenant_id, success_rate"),
        @Index(name = "idx_experience_index_key", columnList = "tenant_id, index_key")
    }
)
public class AgentExperienceIndexEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "index_key", length = 128, nullable = false)
    private String indexKey;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(length = 128, nullable = false)
    private String scenario;

    @Column(name = "intent_type", length = 64)
    private String intentType;

    @Column(name = "workflow_name", length = 128)
    private String workflowName;

    @Column(name = "tool_chain", length = 1000)
    private String toolChain;

    @Column(length = 1000)
    private String keywords;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "data_source", length = 128)
    private String dataSource;

    @Column(name = "feedback_result", length = 32)
    private String feedbackResult;

    @Column(name = "useful_count")
    private long usefulCount;

    @Column(name = "adopted_count")
    private long adoptedCount;

    @Column(name = "resolved_count")
    private long resolvedCount;

    @Column(name = "failed_count")
    private long failedCount;

    @Column(name = "sample_count")
    private long sampleCount;

    @Column(name = "success_rate")
    private double successRate;

    @Column(name = "best_practice", columnDefinition = "TEXT")
    private String bestPractice;

    @Column(name = "avoid_pattern", columnDefinition = "TEXT")
    private String avoidPattern;

    @Column(name = "last_experience_id", length = 64)
    private String lastExperienceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
