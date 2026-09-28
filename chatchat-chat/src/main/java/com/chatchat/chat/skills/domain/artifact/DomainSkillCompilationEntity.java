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

/** Versioned internal Skill IR produced from a retained source artifact. */
@Getter
@Setter
@Entity
@Table(name = "ds_domain_skill_compilation")
public class DomainSkillCompilationEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "skill_id", length = 64, nullable = false)
    private String skillId;
    @Column(name = "source_id", length = 64, nullable = false)
    private String sourceId;
    @Column(name = "ir_schema_version", length = 48, nullable = false)
    private String irSchemaVersion;
    @Column(name = "compiler_version", length = 64, nullable = false)
    private String compilerVersion;
    @Column(name = "compiler_model", length = 200)
    private String compilerModel;
    @Column(name = "compilation_mode", length = 40, nullable = false)
    private String compilationMode;
    @Column(name = "skill_ir_json", nullable = false, length = org.hibernate.Length.LONG32)
    private String skillIrJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void create() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
