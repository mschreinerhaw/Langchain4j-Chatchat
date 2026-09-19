package com.chatchat.api.datascience.skill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "ds_domain_skill_category",
    uniqueConstraints = @UniqueConstraint(name = "uk_domain_skill_category_tenant_name", columnNames = {"tenant_id", "name"}),
    indexes = @Index(name = "idx_domain_skill_category_tenant", columnList = "tenant_id,updated_at"))
public class DomainSkillCategoryEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(length = 120, nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
