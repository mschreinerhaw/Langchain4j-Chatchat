package com.chatchat.api.datascience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Getter
@Setter
@Entity
@Table(name = "ds_python_data_file", indexes = {
        @Index(name = "idx_python_data_owner", columnList = "tenant_id,owner_id,created_at"),
        @Index(name = "idx_python_data_expiry", columnList = "status,expire_at")
})
public class PythonDataFileEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;
    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;
    @Column(name = "file_type", length = 24, nullable = false)
    private String fileType;
    @Column(name = "file_size", nullable = false)
    private long fileSize;
    @Column(name = "file_hash", length = 64, nullable = false)
    private String fileHash;
    @JsonIgnore
    @Column(name = "storage_path", length = 1000)
    private String storagePath;
    @Column(name = "python_path", length = 1000, nullable = false)
    private String pythonPath;
    @Column(length = 1000)
    private String purpose;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(name = "status_message", length = 1000)
    private String statusMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "expire_at")
    private Instant expireAt;

    @PrePersist
    void create() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = "TRANSFERRING";
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
