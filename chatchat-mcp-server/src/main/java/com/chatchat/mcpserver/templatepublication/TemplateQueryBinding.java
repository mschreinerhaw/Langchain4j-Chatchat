package com.chatchat.mcpserver.templatepublication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "mcp_template_query_binding", uniqueConstraints = @UniqueConstraint(
    name = "uk_template_query_service_role_domain_subject",
    columnNames = {"service_id", "role_id", "domain_code", "subject_type", "subject_id"}))
public class TemplateQueryBinding {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "service_id", nullable = false, length = 64)
    private String serviceId;

    @Column(name = "parent_tool_name", nullable = false, length = 128)
    private String parentToolName;

    @Column(name = "role_id", nullable = false, length = 64)
    private String roleId;

    @Column(name = "domain_code", nullable = false, length = 64)
    private String domainCode;

    @Column(name = "subject_type", nullable = false, length = 16)
    private String subjectType = "ROLE";

    @Column(name = "subject_id", nullable = false, length = 128)
    private String subjectId;

    @Lob
    @Column(name = "template_keys_json", nullable = false, columnDefinition = "longtext")
    private String templateKeysJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private long revision = 1L;

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
