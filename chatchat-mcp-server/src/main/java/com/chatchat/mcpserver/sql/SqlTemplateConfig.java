package com.chatchat.mcpserver.sql;

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
@Table(name = "mcp_sql_template")
public class SqlTemplateConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 128)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Lob
    @Column(nullable = false, columnDefinition = "longtext")
    private String sqlTemplate;

    @Lob
    @Column(columnDefinition = "longtext")
    private String parameterSchemaJson;

    @Column(length = 32)
    private String riskLevel = "MEDIUM";

    @Column(length = 100)
    private String category = "sql_diagnostic";

    @Column(length = 64)
    private String databaseType = "generic";

    @Column(length = 64)
    private String datasourceId;

    @Lob
    @Column(columnDefinition = "longtext")
    private String routingLabelsJson;

    @Lob
    @Column(columnDefinition = "longtext")
    private String intentSignalsJson;

    @Column(nullable = false)
    private boolean enabled = true;

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
