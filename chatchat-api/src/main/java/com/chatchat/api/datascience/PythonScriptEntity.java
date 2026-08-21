package com.chatchat.api.datascience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "ds_python_script", uniqueConstraints = @UniqueConstraint(name = "uk_python_script_name", columnNames = {"asset_id", "file_name"}), indexes = {
        @Index(name = "idx_python_script_owner", columnList = "tenant_id,owner_id,updated_at")
})
public class PythonScriptEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;
    @Column(name = "asset_id", length = 64, nullable = false)
    private String assetId;
    @Column(name = "folder_id", length = 64)
    private String folderId;
    @Column(name = "file_name", length = 180, nullable = false)
    private String fileName;
    @Column(length = 300)
    private String title;
    @Lob
    @Column(name = "source_code", nullable = false, columnDefinition = "LONGTEXT")
    private String sourceCode;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(name = "current_version", nullable = false)
    private int currentVersion;
    @Column(name = "last_tested_at")
    private Instant lastTestedAt;
    @Column(name = "last_test_succeeded", nullable = false)
    private boolean lastTestSucceeded;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = "DRAFT";
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
