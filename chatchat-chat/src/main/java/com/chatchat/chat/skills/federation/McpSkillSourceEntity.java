package com.chatchat.chat.skills.federation;

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
@Table(name = "ds_mcp_skill_source", indexes = {
    @Index(name = "idx_mcp_skill_source_tenant", columnList = "tenant_id,updated_at")
})
public class McpSkillSourceEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", length = 64, nullable = false) private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false) private String ownerId;
    @Column(length = 200, nullable = false) private String name;
    @Column(length = 2000, nullable = false) private String endpoint;
    @Column(name = "authorization_header", length = 2000) private String authorizationHeader;
    @Column(name = "default_category", length = 120, nullable = false) private String defaultCategory;
    @Column(name = "allow_private_network", nullable = false) private boolean allowPrivateNetwork;
    @Column(nullable = false) private boolean enabled;
    @Column(length = 24, nullable = false) private String status;
    @Column(name = "last_error", length = 2000) private String lastError;
    @Column(name = "last_discovered_count", nullable = false) private int lastDiscoveredCount;
    @Column(name = "last_synced_at") private Instant lastSyncedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void create() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null || status.isBlank()) status = "NOT_SYNCED";
        if (defaultCategory == null || defaultCategory.isBlank()) defaultCategory = "MCP Skills";
    }

    @PreUpdate void update() { updatedAt = Instant.now(); }
}
