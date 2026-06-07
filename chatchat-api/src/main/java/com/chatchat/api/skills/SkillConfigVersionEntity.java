package com.chatchat.api.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Skill configuration version snapshot.
 */
@Getter
@Setter
@Entity
@Table(name = "skill_config_version")
public class SkillConfigVersionEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(length = 64, nullable = false)
    private String skillId;

    @Column(length = 64)
    private String action;

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
    private String quickQuestionsJson;

    @Column(length = 32)
    private String marketStatus;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (defaultMode == null || defaultMode.isBlank()) {
            defaultMode = "agent_chat";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
