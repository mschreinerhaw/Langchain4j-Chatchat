package com.chatchat.mcpserver.sql;

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

@Getter
@Setter
@Entity
@Table(name = "mcp_sql_metadata_asset_registry")
public class SqlMetadataAssetRegistry {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String datasourceId;

    @Column(nullable = false, length = 200)
    private String databaseName;

    @Column(length = 200)
    private String ownerUser;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 32)
    private String refreshMode = "MANUAL";

    @Column(length = 32)
    private String indexStatus = "PENDING";

    @Column(length = 1000)
    private String indexMessage;

    @Column
    private Instant lastIndexedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
