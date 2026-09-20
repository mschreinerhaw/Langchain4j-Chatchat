package com.chatchat.chat.skills.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Persistent status of an asynchronous domain-skill import. Import payloads remain process-local. */
@Getter
@Setter
@Entity
@Table(name = "ds_domain_skill_import_task")
public class DomainSkillImportTaskEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;
    @Column(name = "import_type", length = 24, nullable = false)
    private String importType;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(name = "requested_name", length = 200)
    private String requestedName;
    @Column(length = 120, nullable = false)
    private String category;
    @Column(name = "source_reference", length = 2000)
    private String sourceReference;
    @Column(name = "skill_id", length = 64)
    private String skillId;
    @Column(name = "error_message", length = 2000)
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
