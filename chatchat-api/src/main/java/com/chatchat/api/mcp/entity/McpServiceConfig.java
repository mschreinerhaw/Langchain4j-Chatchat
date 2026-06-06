package com.chatchat.api.mcp.entity;

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

/**
 * MCP service connection configuration.
 */
@Getter
@Setter
@Entity
@Table(name = "mcp_service_config")
public class McpServiceConfig {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 512, nullable = false)
    private String baseUrl;

    @Column(length = 256, nullable = false)
    private String toolDiscoveryPath = "/tools";

    @Column(length = 256, nullable = false)
    private String toolInvokePath = "/tools/call";

    @Column(length = 1024)
    private String authToken;

    @Column(length = 4000)
    private String customHeadersJson;

    @Column(length = 32, nullable = false)
    private String protocol = "legacy_http";

    @Column(length = 512)
    private String stdioCommand;

    @Column(length = 4000)
    private String stdioArgsJson;

    @Column(length = 4000)
    private String stdioEnvJson;

    @Column(length = 1024)
    private String stdioWorkingDirectory;

    @Column(nullable = false)
    private boolean proxyEnabled = false;

    @Column(length = 16, nullable = false)
    private String proxyType = "http";

    @Column(length = 255)
    private String proxyHost;

    @Column
    private Integer proxyPort;

    @Column(length = 128)
    private String proxyUsername;

    @Column(length = 512)
    private String proxyPassword;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int timeoutMs = 20000;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
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
}
