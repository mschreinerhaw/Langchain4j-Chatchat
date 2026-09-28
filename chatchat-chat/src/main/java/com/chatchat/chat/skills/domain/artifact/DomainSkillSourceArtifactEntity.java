package com.chatchat.chat.skills.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Immutable original and deterministic parse artifacts retained for future recompilation. */
@Getter
@Setter
@Entity
@Table(name = "ds_domain_skill_source")
public class DomainSkillSourceArtifactEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "skill_id", length = 64, nullable = false)
    private String skillId;
    @Column(name = "source_type", length = 32, nullable = false)
    private String sourceType;
    @Column(name = "source_reference", length = 2000)
    private String sourceReference;
    @Column(name = "original_file_name", length = 300)
    private String originalFileName;
    @Column(name = "original_hash", length = 64, nullable = false)
    private String originalHash;
    @Column(name = "original_artifact", nullable = false, length = org.hibernate.Length.LONG32)
    private byte[] originalArtifact;
    @Column(name = "parsed_document_json", nullable = false, length = org.hibernate.Length.LONG32)
    private String parsedDocumentJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void create() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
