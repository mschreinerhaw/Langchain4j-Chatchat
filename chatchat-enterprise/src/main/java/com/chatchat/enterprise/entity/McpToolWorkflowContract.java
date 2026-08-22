package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "mcp_tool_workflow_contract",
    uniqueConstraints = @UniqueConstraint(name = "uk_mcp_tool_contract_version",
        columnNames = {"tool_id", "contract_version"}),
    indexes = {
        @Index(name = "idx_mcp_tool_contract_active", columnList = "tool_id,status"),
        @Index(name = "idx_mcp_tool_contract_checksum", columnList = "contract_checksum")
    })
public class McpToolWorkflowContract extends EnterpriseAuditable {

    @Column(name = "tool_id", length = 64, nullable = false)
    private String toolId;

    @Column(name = "contract_version", nullable = false)
    private long contractVersion;

    @Column(name = "schema_version", length = 64, nullable = false)
    private String schemaVersion;

    @Column(name = "workflow_role", length = 32, nullable = false)
    private String workflowRole;

    @Column(name = "protocol_family", length = 64)
    private String protocolFamily;

    @Column(name = "input_envelope", length = 32)
    private String inputEnvelope;

    @Column(name = "status", length = 16, nullable = false)
    private String status = "DRAFT";

    @Column(name = "contract_checksum", length = 64, nullable = false)
    private String contractChecksum;

    @Lob
    @Column(name = "input_schema_json", columnDefinition = "LONGTEXT")
    private String inputSchemaJson;

    @Lob
    @Column(name = "output_schema_json", columnDefinition = "LONGTEXT")
    private String outputSchemaJson;

    @Lob
    @Column(name = "extensions_json", columnDefinition = "LONGTEXT")
    private String extensionsJson;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 128)
    private String publishedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;
}
