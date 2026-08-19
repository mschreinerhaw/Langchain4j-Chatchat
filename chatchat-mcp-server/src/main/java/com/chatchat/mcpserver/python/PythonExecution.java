package com.chatchat.mcpserver.python;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name="mcp_python_runtime_execution",indexes=@Index(name="idx_mcp_python_exec_asset",columnList="tenant_id,asset_id,started_at"))
public class PythonExecution {
    @Id @Column(length=64) private String id;
    @Column(name="tenant_id",length=64,nullable=false) private String tenantId;
    @Column(name="owner_id",length=64,nullable=false) private String ownerId;
    @Column(name="asset_id",length=64,nullable=false) private String assetId;
    @Column(name="environment_id",length=64,nullable=false) private String environmentId;
    @Column(name="template_id",length=64) private String templateId;
    @Column(name="container_id",length=128) private String containerId;
    @Column(length=24,nullable=false) private String status;
    @Lob @Column(columnDefinition="LONGTEXT") private String stdout;
    @Lob @Column(columnDefinition="LONGTEXT") private String stderr;
    @Column(name="exit_code") private Integer exitCode;
    @Column(name="duration_ms") private Long durationMs;
    @Column(name="started_at",nullable=false) private Instant startedAt;
    @Column(name="finished_at") private Instant finishedAt;
    @PrePersist void create(){if(id==null)id=UUID.randomUUID().toString();if(startedAt==null)startedAt=Instant.now();}
}
