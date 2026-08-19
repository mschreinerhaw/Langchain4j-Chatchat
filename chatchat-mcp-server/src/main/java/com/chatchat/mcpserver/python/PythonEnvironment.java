package com.chatchat.mcpserver.python;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name="mcp_python_environment",indexes=@Index(name="idx_mcp_python_env_status",columnList="status,updated_at"))
public class PythonEnvironment {
    @Id @Column(length=64) private String id;
    @Column(length=160,nullable=false) private String name;
    @Column(length=1000) private String description;
    @Column(name="docker_image",length=300,nullable=false) private String dockerImage;
    @Column(name="python_version",length=32,nullable=false) private String pythonVersion;
    @Column(name="cpu_limit",length=24,nullable=false) private String cpuLimit;
    @Column(name="memory_limit",length=24,nullable=false) private String memoryLimit;
    @Column(name="timeout_seconds",nullable=false) private int timeoutSeconds;
    @Column(name="network_enabled",nullable=false) private boolean networkEnabled;
    @Column(name="version_number",nullable=false) private int versionNumber;
    @Column(length=24,nullable=false) private String status;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){if(id==null)id=UUID.randomUUID().toString();Instant now=Instant.now();createdAt=now;updatedAt=now;if(versionNumber<1)versionNumber=1;if(status==null)status="DRAFT";}
    @PreUpdate void update(){updatedAt=Instant.now();}
}
