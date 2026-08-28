package com.chatchat.chat.task.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "agent_optimization_proposal", indexes = {
    @Index(name = "idx_agent_optimization_queue", columnList = "tenant_id, proposal_status, created_at"),
    @Index(name = "idx_agent_optimization_agent", columnList = "tenant_id, agent_id, created_at")
})
public class AgentOptimizationProposalEntity {

    @Id
    @Column(name = "proposal_id", length = 64, nullable = false)
    private String proposalId;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId;
    @Column(name = "proposal_type", length = 32, nullable = false)
    private String proposalType;
    @Column(name = "proposal_status", length = 24, nullable = false)
    private String status;
    @Column(name = "source_experience_ids_json", columnDefinition = "TEXT", nullable = false)
    private String sourceExperienceIdsJson;
    @Column(name = "patch_json", columnDefinition = "LONGTEXT", nullable = false)
    private String patchJson;
    @Column(name = "evidence_json", columnDefinition = "LONGTEXT", nullable = false)
    private String evidenceJson;
    @Column(name = "regression_report_json", columnDefinition = "LONGTEXT")
    private String regressionReportJson;
    @Column(name = "canary_metrics_json", columnDefinition = "LONGTEXT")
    private String canaryMetricsJson;
    @Column(name = "canary_percent")
    private Integer canaryPercent;
    @Column(name = "created_by", length = 128, nullable = false)
    private String createdBy;
    @Column(name = "reviewed_by", length = 128)
    private String reviewedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;
}
