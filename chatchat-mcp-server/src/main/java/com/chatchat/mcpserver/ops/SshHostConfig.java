package com.chatchat.mcpserver.ops;

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
@Table(name = "mcp_ops_ssh_host")
public class SshHostConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 200)
    private String name;

    @Column(unique = true, length = 128)
    private String toolName;

    @Column(length = 200)
    private String title;

    @Column(length = 1200)
    private String description;

    @Column(nullable = false, length = 300)
    private String hostname;

    @Column(nullable = false)
    private int port = 22;

    @Column(nullable = false, length = 200)
    private String username;

    @Column(nullable = false, length = 32)
    private String authType = "PASSWORD";

    @Column(length = 1000)
    private String password;

    @Lob
    @Column
    private String privateKey;

    @Column(length = 1000)
    private String passphrase;

    @Column(length = 300)
    private String hostKeyFingerprint;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false, length = 32)
    private String environment = "DEV";

    @Column(length = 1000)
    private String tags;

    @Lob
    @Column
    private String allowedCommandsJson;

    @Column(nullable = false, length = 32)
    private String runtimeAction = "confirm_required";

    @Column(nullable = false)
    private int connectTimeoutMs = 10000;

    @Column(nullable = false)
    private int commandTimeoutMs = 30000;

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
