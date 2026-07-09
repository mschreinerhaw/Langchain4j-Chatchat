package com.chatchat.chat.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistent skill configuration.
 */
@Getter
@Setter
@Entity
@Table(name = "skill_config")
public class SkillConfigEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(length = 128, nullable = false)
    private String label;

    @Column(length = 1024)
    private String description;

    @Column(columnDefinition = "TEXT")
    private String usageScenariosJson;

    @Column(columnDefinition = "TEXT")
    private String skillTagsJson;

    @Column(length = 32, nullable = false)
    private String defaultMode = "agent_chat";

    @Column(length = 128)
    private String modelName;

    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(columnDefinition = "TEXT")
    private String firstUseGreeting;

    @Column(columnDefinition = "TEXT")
    private String preferredToolPrefixesJson;

    @Column(columnDefinition = "TEXT")
    private String boundMcpServiceIdsJson;

    @Column(columnDefinition = "TEXT")
    private String boundMcpToolNamesJson;

    @Column(columnDefinition = "TEXT")
    private String boundDocumentIdsJson;

    @Column(columnDefinition = "TEXT")
    private String boundDocumentTagsJson;

    @Column(columnDefinition = "TEXT")
    private String toolConfigsJson;

    @Column(columnDefinition = "TEXT")
    private String routingSettingsJson;

    @Column(columnDefinition = "TEXT")
    private String workflowConfigJson;

    @Column(columnDefinition = "TEXT")
    private String quickQuestionsJson;

    @Column(length = 32, nullable = false)
    private String marketStatus = "draft";

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean defaultAgent = false;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Performs the on create operation.
     */
    @PrePersist
    public void onCreate() {
        if (defaultMode == null || defaultMode.isBlank()) {
            defaultMode = "agent_chat";
        }
        if (marketStatus == null || marketStatus.isBlank()) {
            marketStatus = "draft";
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /**
     * Performs the on update operation.
     */
    @PreUpdate
    public void onUpdate() {
        if (defaultMode == null || defaultMode.isBlank()) {
            defaultMode = "agent_chat";
        }
        if (marketStatus == null || marketStatus.isBlank()) {
            marketStatus = "draft";
        }
        updatedAt = Instant.now();
    }
}
