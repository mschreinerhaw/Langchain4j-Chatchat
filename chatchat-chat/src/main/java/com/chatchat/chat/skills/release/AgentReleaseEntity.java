package com.chatchat.chat.skills.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "agent_release", indexes = {
    @Index(name = "idx_agent_release_status", columnList = "agent_id, release_status, release_version")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_agent_release_version", columnNames = {"agent_id", "release_version"})
})
public class AgentReleaseEntity {

    @Id
    @Column(name = "release_id", length = 64, nullable = false, updatable = false)
    private String releaseId;

    @Column(name = "agent_id", length = 64, nullable = false, updatable = false)
    private String agentId;

    @Column(name = "release_version", nullable = false, updatable = false)
    private Integer releaseVersion;

    @Column(name = "release_status", length = 24, nullable = false)
    private String status;

    @Column(name = "artifact_checksum", length = 64, nullable = false, updatable = false)
    private String artifactChecksum;

    @Column(name = "artifact_json", columnDefinition = "LONGTEXT", nullable = false, updatable = false)
    private String artifactJson;

    @Column(name = "quality_report_json", columnDefinition = "LONGTEXT", nullable = false, updatable = false)
    private String qualityReportJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
