package com.chatchat.mcpserver.routing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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
@Table(name = "mcp_execution_target")
public class ExecutionTargetConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 128)
    private String targetKey;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1200)
    private String description;

    @Column(nullable = false, length = 32)
    private String assetType = "SSH_HOST";

    @Column(length = 32)
    private String environment;

    @Column(nullable = false, length = 32)
    private String selectorType = "LABEL";

    @Column(nullable = false, length = 300)
    private String selectorValue;

    @Lob
    @Column
    private String labelsJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int priority = 100;

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
