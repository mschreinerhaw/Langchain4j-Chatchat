package com.chatchat.mcpserver.sql.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
    @Column(nullable = false, length = org.hibernate.Length.LONG32)
    private String sqlTemplate;
    @Column(length = org.hibernate.Length.LONG32)
    private String parameterSchemaJson;

    @Column(length = 32)
    private String riskLevel = "MEDIUM";

    @Column(length = 100)
    private String category = "sql_diagnostic";

    @Column(length = 64)
    private String databaseType = "generic";

    @Column(length = 64)
    private String datasourceId;
    @Column(length = org.hibernate.Length.LONG32)
    private String routingLabelsJson;
    @Column(length = org.hibernate.Length.LONG32)
    private String intentSignalsJson;
    @Column(length = org.hibernate.Length.LONG32)
    private String evidencePolicyJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name="publication_group_id", length=64)
    private String publicationGroupId;
    @Column(name="publication_version")
    private Integer publicationVersion;
    @Column(name="author_tenant_id", length=64)
    private String authorTenantId;
    @Column(name="author_id", length=64)
    private String authorId;
    @Column(name="publication_state", length=24)
    private String publicationState;
    @Column(name="publication_lock")
    private Long publicationLock = 0L;
    @Column(name="authoring_source_json", length=org.hibernate.Length.LONG32)
    private String authoringSourceJson;
    @Column(name="sql_test_evidence_json", length=org.hibernate.Length.LONG32)
    private String sqlTestEvidenceJson;
    @Column(name="released_capability_id", length=64)
    private String releasedCapabilityId;
    @Column(name="draft_request_digest", length=64)
    private String draftRequestDigest;

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
