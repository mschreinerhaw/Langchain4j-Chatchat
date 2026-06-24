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
@Table(name = "mcp_ops_command_template")
public class CommandTemplateConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 128)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 2000)
    private String commandTemplate;

    @Lob
    @Column
    private String parameterSchemaJson;

    @Column(length = 32)
    private String riskLevel = "LOW";

    @Column(length = 100)
    private String category = "system_diagnostic";

    @Lob
    @Column
    private String intentSignalsJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 32)
    private String runtimeAction = "confirm_required";

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
