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

    @Column(length = 16000)
    private String usageScenariosJson;

    @Column(length = 16000)
    private String skillTagsJson;

    @Column(length = 32, nullable = false)
    private String defaultMode = "agent_chat";

    @Column(length = 128)
    private String modelName;

    @Column(length = 16000)
    private String systemPrompt;

    @Column(length = 4000)
    private String firstUseGreeting;

    @Column(length = 16000)
    private String preferredToolPrefixesJson;

    @Column(length = 16000)
    private String boundMcpServiceIdsJson;

    @Column(length = 16000)
    private String boundMcpToolNamesJson;

    @Column(length = 16000)
    private String boundDocumentIdsJson;

    @Column(length = 16000)
    private String boundDocumentTagsJson;

    @Column(length = 16000)
    private String toolConfigsJson;

    @Column(length = 4000)
    private String routingSettingsJson;

    @Column(length = 16000)
    private String workflowConfigJson;

    @Column(length = 16000)
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
