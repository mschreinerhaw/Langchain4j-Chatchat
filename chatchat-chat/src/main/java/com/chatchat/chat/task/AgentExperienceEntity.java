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
    name = "agent_experience",
    indexes = {
        @Index(name = "idx_agent_experience_tenant_score", columnList = "tenant_id, feedback_score"),
        @Index(name = "idx_agent_experience_scenario", columnList = "tenant_id, scenario_key"),
        @Index(name = "idx_agent_experience_task", columnList = "tenant_id, task_id")
    }
)
public class AgentExperienceEntity {

    @Id
    @Column(name = "experience_id", length = 64, nullable = false)
    private String experienceId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "scenario_key", length = 128, nullable = false)
    private String scenarioKey;

    @Column(name = "scenario_name", length = 256)
    private String scenarioName;

    @Column(length = 4000)
    private String question;

    @Column(name = "answer_summary", length = 4000)
    private String answerSummary;

    @Column(name = "feedback_useful")
    private Boolean feedbackUseful;

    @Column(name = "feedback_adopted")
    private Boolean feedbackAdopted;

    @Column(name = "feedback_resolved")
    private Boolean feedbackResolved;

    @Column(name = "feedback_reason_category", length = 64)
    private String feedbackReasonCategory;

    @Column(name = "feedback_comment", length = 1000)
    private String feedbackComment;

    @Column(name = "feedback_score")
    private Integer feedbackScore;

    @Column(name = "attribution_source", length = 32)
    private String attributionSource;

    @Column(name = "attribution_summary", length = 1000)
    private String attributionSummary;

    @Column(name = "success_pattern_json", length = 4000)
    private String successPatternJson;

    @Column(name = "improvement_suggestions_json", length = 4000)
    private String improvementSuggestionsJson;

    @Column(name = "primary_factors_json", length = 4000)
    private String primaryFactorsJson;

    @Column(name = "model_raw_output", length = 4000)
    private String modelRawOutput;

    @Column(name = "create_time", nullable = false)
    private Instant createTime;

    @Column(name = "update_time", nullable = false)
    private Instant updateTime;

    @PrePersist
    public void onCreate() {
        if (experienceId == null || experienceId.isBlank()) {
            experienceId = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createTime == null) {
            createTime = now;
        }
        updateTime = now;
    }

    @PreUpdate
    public void onUpdate() {
        updateTime = Instant.now();
    }
}
