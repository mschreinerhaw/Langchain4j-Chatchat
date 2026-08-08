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
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "mcp_ops_jmx_template")
public class JmxTemplateConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 128)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 1000)
    private String serviceUrl;

    @Column(length = 200)
    private String username;

    @Column(length = 1000)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Lob
    @Column(nullable = false, columnDefinition = "longtext")
    private String queriesJson;

    @Lob
    @Column(columnDefinition = "longtext")
    private String intentSignalsJson;

    @Column(nullable = false, length = 100)
    private String category = "java_monitoring";

    @Column(nullable = false, length = 32)
    private String riskLevel = "LOW";

    @Column(nullable = false, length = 32)
    private String runtimeAction = "readonly";

    @Column(nullable = false)
    private int timeoutMs = 10000;

    @Column(nullable = false)
    private boolean enabled;

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
