package com.chatchat.api.datascience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name="mcp_python_execution",indexes=@Index(name="idx_python_execution_owner",columnList="tenant_id,owner_id,started_at"))
public class PythonExecutionEntity {
    @Id @Column(length=64) private String id;
    @Column(name="tenant_id",length=64,nullable=false) private String tenantId;
    @Column(name="owner_id",length=64,nullable=false) private String ownerId;
    @Column(name="asset_id",length=64,nullable=false) private String assetId;
    @Column(name="script_id",length=64) private String scriptId;
    @Column(name="template_id",length=64) private String templateId;
    @Column(length=24,nullable=false) private String status;
    @Lob @Column(name="parameters_json",columnDefinition="TEXT") private String parametersJson;
    @Lob @Column(columnDefinition="LONGTEXT") private String stdout;
    @Lob @Column(columnDefinition="LONGTEXT") private String stderr;
    @Lob @Column(name="result_json",columnDefinition="LONGTEXT") private String resultJson;
    @Column(name="exit_code") private Integer exitCode;
    @Column(name="started_at",nullable=false) private Instant startedAt;
    @Column(name="finished_at") private Instant finishedAt;
    @Column(name="duration_ms") private Long durationMs;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID().toString(); if(startedAt==null) startedAt=Instant.now(); }
}
