package com.chatchat.mcpserver.templatepublication.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Each row is a version; published definition/schema/test evidence are immutable. */
@Entity @Table(name="mcp_tool_release", uniqueConstraints=@UniqueConstraint(columnNames={"logical_id","release_number"}))
@Getter @Setter
public class ToolRelease {
    @Id @Column(length=64) private String id;
    @Column(name="logical_id", nullable=false, length=64) private String logicalId;
    @Column(name="tenant_id", nullable=false, length=64) private String tenantId;
    @Column(name="release_number", nullable=false) private int releaseNumber;
    @Column(nullable=false, length=24) private String state;
    @Column(nullable=false, length=64) private String kind;
    @Column(nullable=false, length=256) private String title;
    @Column(length=org.hibernate.Length.LONG32) private String description;
    @Column(name="definition_json", nullable=false, length=org.hibernate.Length.LONG32) private String definitionJson;
    @Column(name="input_schema_json", nullable=false, length=org.hibernate.Length.LONG32) private String inputSchemaJson;
    @Column(name="output_schema_json", nullable=false, length=org.hibernate.Length.LONG32) private String outputSchemaJson;
    @Column(name="test_evidence_json", length=org.hibernate.Length.LONG32) private String testEvidenceJson;
    @Column(name="binding_id", length=64) private String bindingId;
    @Column(name="actor_id", nullable=false, length=64) private String actorId;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="published_at") private Instant publishedAt;
    @Version private Long version;
}
