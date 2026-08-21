package com.chatchat.mcpserver.python;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "mcp_python_template_asset", indexes = {@Index(name = "idx_mcp_python_template_status", columnList = "tenant_id,status,updated_at"), @Index(name = "idx_mcp_python_template_env", columnList = "environment_id,status")})
public class PythonTemplate {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;
    @Column(name = "asset_id", length = 64, nullable = false)
    private String assetId;
    @Column(name = "asset_name", length = 160)
    private String assetName;
    @Column(name = "asset_description", length = 2000)
    private String assetDescription;
    @Column(name = "environment_id", length = 64, nullable = false)
    private String environmentId;
    @Column(name = "script_file_name", length = 180)
    private String scriptFileName;
    @Column(name = "template_name", length = 200, nullable = false)
    private String templateName;
    @Column(name = "tool_name", length = 200, nullable = false, unique = true)
    private String toolName;
    @Column(length = 40, nullable = false)
    private String version;
    @Column(length = 4000, nullable = false)
    private String scenario;
    @Column(length = 3000, nullable = false)
    private String description;
    @Column(length = 1000)
    private String keywords;
    @Column(length = 120)
    private String domain;
    @Column(name = "category_id", length = 64)
    private String categoryId;
    @Lob
    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;
    @Lob
    @Column(name = "output_schema_json", columnDefinition = "TEXT")
    private String outputSchemaJson;
    @Lob
    @Column(name = "source_ciphertext", nullable = false, columnDefinition = "LONGTEXT")
    private String sourceCiphertext;
    @Column(name = "source_hash", length = 64, nullable = false)
    private String sourceHash;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
