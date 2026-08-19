package com.chatchat.api.datascience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity
@Table(name = "ds_python_asset", indexes = {
    @Index(name = "idx_python_asset_owner", columnList = "tenant_id,owner_id,status")
})
public class PythonAssetEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", length = 64, nullable = false) private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false) private String ownerId;
    @Column(length = 160, nullable = false) private String name;
    @Column(length = 2000) private String description;
    @Column(name = "mcp_environment_id", length = 64, nullable = false) private String mcpEnvironmentId;
    @Column(name = "mcp_environment_version", nullable = false) private int mcpEnvironmentVersion;
    @Column(name = "docker_image", length = 300, nullable = false) private String dockerImage;
    @Column(name = "python_version", length = 32) private String pythonVersion;
    @Column(length = 24, nullable = false) private String status;
    @Column(name = "container_name", length = 128) private String containerName;
    @Column(name = "workspace_path", length = 600) private String workspacePath;
    @Column(name = "cpu_limit", length = 24) private String cpuLimit;
    @Column(name = "memory_limit", length = 24) private String memoryLimit;
    @Column(name = "network_enabled", nullable = false) private boolean networkEnabled;
    @Column(name = "dependencies_json", columnDefinition = "TEXT") private String dependenciesJson;
    @Column(name = "status_message", length = 1000) private String statusMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void create(){ if(id==null) id=UUID.randomUUID().toString(); Instant now=Instant.now(); createdAt=now; updatedAt=now; }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
}
