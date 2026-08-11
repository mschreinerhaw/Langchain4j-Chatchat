package com.chatchat.chat.uiartifact;

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

@Getter
@Setter
@Entity
@Table(
    name = "ui_artifact",
    indexes = {
        @Index(name = "idx_ui_artifact_tenant_created", columnList = "tenant_id, created_at"),
        @Index(name = "idx_ui_artifact_expiry", columnList = "status, expires_at"),
        @Index(name = "idx_ui_artifact_task", columnList = "tenant_id, task_id")
    }
)
public class UiArtifactEntity {

    @Id
    @Column(name = "artifact_id", length = 128, nullable = false)
    private String artifactId;

    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;

    @Column(name = "task_id", length = 128)
    private String taskId;

    @Column(name = "schema_version", length = 64, nullable = false)
    private String schemaVersion;

    @Column(name = "catalog_version", length = 64, nullable = false)
    private String catalogVersion;

    @Column(nullable = false)
    private int revision = 1;

    @Column(length = 32, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "store_type", length = 32, nullable = false)
    private String storeType;

    @Column(name = "manifest_key", length = 512, nullable = false)
    private String manifestKey;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "resource_count", nullable = false)
    private int resourceCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean readableAt(Instant instant) {
        return "ACTIVE".equals(status) && (expiresAt == null || expiresAt.isAfter(instant));
    }
}
