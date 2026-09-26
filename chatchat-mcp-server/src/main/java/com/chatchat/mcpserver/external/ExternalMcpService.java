package com.chatchat.mcpserver.external;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An outbound MCP endpoint and its discovered, parent-bound template snapshot. */
@Entity
@Table(name = "mcp_external_service")
public class ExternalMcpService {
    @Id @Column(length = 64) private String id;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 2000) private String endpoint;
    @Column(name = "authorization_header", length = 2000) private String authorization;
    @Column(nullable = false, length = 128) private String parentToolName;
    @Column(nullable = false, length = 64) private String workflowId = "mcp_streamable_http";
    @Column(nullable = false) private boolean enabled;
    @Column(length = org.hibernate.Length.LONG32) private String templatesJson;
    private Instant discoveredAt;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    @PrePersist void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
        updatedAt = createdAt;
    }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAuthorization() { return authorization; }
    public void setAuthorization(String authorization) { this.authorization = authorization; }
    public String getParentToolName() { return parentToolName; }
    public void setParentToolName(String parentToolName) { this.parentToolName = parentToolName; }
    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTemplatesJson() { return templatesJson; }
    public void setTemplatesJson(String templatesJson) { this.templatesJson = templatesJson; }
    public Instant getDiscoveredAt() { return discoveredAt; }
    public void setDiscoveredAt(Instant discoveredAt) { this.discoveredAt = discoveredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
