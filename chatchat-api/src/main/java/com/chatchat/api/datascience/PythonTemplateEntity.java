package com.chatchat.api.datascience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name="mcp_python_template", indexes={
    @Index(name="idx_python_template_status",columnList="tenant_id,status,published_at"),
    @Index(name="idx_python_template_owner",columnList="tenant_id,owner_id")
})
public class PythonTemplateEntity {
    @Id @Column(length=64) private String id;
    @Column(name="tenant_id",length=64,nullable=false) private String tenantId;
    @Column(name="owner_id",length=64,nullable=false) private String ownerId;
    @Column(name="asset_id",length=64,nullable=false) private String assetId;
    @Column(name="script_id",length=64,nullable=false) private String scriptId;
    @Column(name="script_version",nullable=false) private int scriptVersion;
    @Column(name="template_name",length=200,nullable=false) private String templateName;
    @Column(length=40,nullable=false) private String version;
    @Column(length=4000,nullable=false) private String scenario;
    @Column(length=3000,nullable=false) private String description;
    @Column(length=1000) private String keywords;
    @Column(length=120) private String domain;
    @Lob @Column(name="input_schema_json",columnDefinition="TEXT") private String inputSchemaJson;
    @Lob @Column(name="output_schema_json",columnDefinition="TEXT") private String outputSchemaJson;
    @Lob @Column(name="source_snapshot",nullable=false,columnDefinition="LONGTEXT") private String sourceSnapshot;
    @Lob @Column(name="search_text",nullable=false,columnDefinition="TEXT") private String searchText;
    @Column(length=24,nullable=false) private String status;
    @Column(name="index_status",length=24,nullable=false) private String indexStatus;
    @Column(name="runtime_status",length=24,nullable=false) private String runtimeStatus;
    @Column(name="mcp_sync_status",length=24,nullable=false) private String mcpSyncStatus;
    @Column(name="mcp_sync_message",length=1000) private String mcpSyncMessage;
    @Column(name="tool_name",length=200,nullable=false,unique=true) private String toolName;
    @Column(name="published_at",nullable=false) private Instant publishedAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID().toString(); Instant now=Instant.now(); if(publishedAt==null) publishedAt=now; updatedAt=now; }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
}
