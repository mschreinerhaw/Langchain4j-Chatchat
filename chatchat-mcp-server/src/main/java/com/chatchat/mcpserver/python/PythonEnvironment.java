package com.chatchat.mcpserver.python;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "mcp_python_environment", indexes = @Index(name = "idx_mcp_python_env_status", columnList = "status,updated_at"))
public class PythonEnvironment {
    @Id
    @Column(length = 64)
    private String id;
    @Column(length = 160, nullable = false)
    private String name;
    @Column(length = 1000)
    private String description;
    @Column(name = "docker_image", length = 300, nullable = false)
    private String dockerImage;
    @Column(name = "python_version", length = 32, nullable = false)
    private String pythonVersion;
    @Column(name = "cpu_limit", length = 24, nullable = false)
    private String cpuLimit;
    @Column(name = "memory_limit", length = 24, nullable = false)
    private String memoryLimit;
    @Column(name = "disk_limit", length = 24, nullable = false)
    private String diskLimit;
    @Column(name = "tmpfs_limit", length = 24, nullable = false)
    private String tmpfsLimit;
    @Column(name = "runtime_user", length = 64, nullable = false)
    private String runtimeUser;
    @Column(name = "network_policy", length = 24, nullable = false)
    private String networkPolicy;
    @Column(name = "network_name", length = 128)
    private String networkName;
    @Lob
    @Column(name = "requirements_json", columnDefinition = "TEXT", nullable = false)
    private String requirementsJson;
    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;
    @Column(name = "network_enabled", nullable = false)
    private boolean networkEnabled;
    @Column(name = "version_number", nullable = false)
    private int versionNumber;
    @Column(length = 24, nullable = false)
    private String status;
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
        if (versionNumber < 1) versionNumber = 1;
        if (status == null) status = "DRAFT";
        if (diskLimit == null) diskLimit = "2g";
        if (tmpfsLimit == null) tmpfsLimit = "512m";
        if (runtimeUser == null) runtimeUser = "10001:10001";
        if (networkPolicy == null) networkPolicy = "NONE";
        if (requirementsJson == null) requirementsJson = "[]";
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
