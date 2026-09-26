package com.chatchat.chat.skills.domain;

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
@Table(name = "ds_domain_skill", indexes = {
    @Index(name = "idx_domain_skill_owner", columnList = "tenant_id,owner_id,updated_at"),
    @Index(name = "idx_domain_skill_status", columnList = "tenant_id,status,updated_at"),
    @Index(name = "idx_domain_skill_category", columnList = "tenant_id,category,updated_at"),
    @Index(name = "idx_domain_skill_federated_source", columnList = "tenant_id,federated_source_id")
})
public class DomainSkillEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;
    @Column(length = 200, nullable = false)
    private String name;
    @Column(length = 2000)
    private String description;
    @Column(length = 120, nullable = false)
    private String category;
    @Column(name = "markdown_content", nullable = false, length = org.hibernate.Length.LONG32)
    private String markdownContent;
    @Column(name = "search_text", length = 4000, nullable = false)
    private String searchText;
    @Column(name = "source_type", length = 24, nullable = false)
    private String sourceType;
    @Column(name = "original_file_name", length = 300)
    private String originalFileName;
    @Column(name = "federated_source_id", length = 64)
    private String federatedSourceId;
    @Column(name = "federated_source_name", length = 200)
    private String federatedSourceName;
    @Column(name = "federated_skill_uri", length = 2000)
    private String federatedSkillUri;
    @Column(name = "federated_digest", length = 80)
    private String federatedDigest;
    @Column(name = "federated_manifest_json", length = org.hibernate.Length.LONG32)
    private String federatedManifestJson;
    @Column(name = "federated_synced_at")
    private Instant federatedSyncedAt;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(name = "publication_dirty", nullable = false)
    private boolean publicationDirty;
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean builtin;
    @Column(name = "published_at")
    private Instant publishedAt;
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
        if (status == null || status.isBlank()) status = "DRAFT";
        if (sourceType == null || sourceType.isBlank()) sourceType = "EDITOR";
        refreshSearchText();
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
        refreshSearchText();
    }

    private void refreshSearchText() {
        String value = String.join("\n", safe(name), safe(category), safe(description), safe(markdownContent));
        searchText = value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    private String safe(String value) { return value == null ? "" : value; }
}
