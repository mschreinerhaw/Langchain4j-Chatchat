package com.chatchat.api.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "analysis_evidence_archive", indexes =
    @Index(name = "idx_analysis_evidence_owner", columnList = "tenant_id, user_id, run_id"))
public class AnalysisEvidenceArchiveEntity {
    @Id @Column(name = "archive_id", length = 64, nullable = false)
    String archiveId;
    @Column(name = "tenant_id", length = 128, nullable = false)
    String tenantId;
    @Column(name = "user_id", length = 128, nullable = false)
    String userId;
    @Column(name = "run_id", length = 128, nullable = false)
    String runId;
    @Column(name = "sha256", length = 64, nullable = false)
    String sha256;
    @Column(name = "byte_length", nullable = false)
    long byteLength;
    @Column(name = "bundle_json", length = org.hibernate.Length.LONG32, nullable = false)
    String bundleJson;
    @Column(name = "created_at_epoch_ms", nullable = false)
    long createdAtEpochMs;
    @Column(name = "index_status", length = 16)
    String indexStatus;

    protected AnalysisEvidenceArchiveEntity() { }

    AnalysisEvidenceArchiveEntity(String archiveId, String tenantId, String userId, String runId,
                                  String sha256, long byteLength, String bundleJson) {
        this.archiveId = archiveId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.runId = runId;
        this.sha256 = sha256;
        this.byteLength = byteLength;
        this.bundleJson = bundleJson;
        this.createdAtEpochMs = System.currentTimeMillis();
        this.indexStatus = "PENDING";
    }
}
