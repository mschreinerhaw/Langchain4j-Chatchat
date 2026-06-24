package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.routing.AssetExecutionTargetBinding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "mcp_sql_datasource")
public class SqlDatasourceConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(unique = true, length = 128)
    private String toolName;

    @Column(length = 200)
    private String title;

    @Column(length = 1200)
    private String description;

    @Column(nullable = false, length = 2000)
    private String jdbcUrl;

    @Column(length = 500)
    private String driverClass;

    @Column(length = 64)
    private String databaseType = "generic";

    @Column(length = 500)
    private String username;

    @Column(length = 1000)
    private String password;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false, length = 32)
    private String environment = "DEV";

    @Column(nullable = false, length = 32)
    private String runtimeAction = "confirm_required";

    @Lob
    @Column
    private String routingLabelsJson;

    @Lob
    @Column
    private String capabilitiesJson;

    @Transient
    private List<String> routingLabels = new ArrayList<>();

    @Transient
    private List<String> capabilities = new ArrayList<>();

    @Transient
    private List<AssetExecutionTargetBinding> executionTargets = new ArrayList<>();

    @Column(nullable = false)
    private int defaultTimeoutSeconds = 30;

    @Column(nullable = false)
    private int defaultMaxRows = 1000;

    @Lob
    @Column
    private String sensitiveTablesJson;

    @Lob
    @Column
    private String sensitiveFieldsJson;

    @Lob
    @Column
    private String allowedTablesJson;

    @Lob
    @Column
    private String allowedTemplatesJson;

    @Lob
    @Column
    private String governanceJson;

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
