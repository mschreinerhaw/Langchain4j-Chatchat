package com.chatchat.api.runtime;

import jakarta.persistence.*;

/** A database lock and durable wake cursor for one tenant/user/run. No process-local authority. */
@Entity
@Table(name = "runtime_evidence_partition")
@org.hibernate.annotations.Check(constraints = "analysis_rounds >= 0 and analysis_rounds <= max_rounds and max_rounds between 1 and 3 and consumed_revision <= observation_revision")
public class RuntimeEvidencePartitionEntity {
    @Id @Column(length = 64) String id;
    @Column(name = "tenant_id", length = 128, nullable = false) String tenantId;
    @Column(name = "user_id", length = 128, nullable = false) String userId;
    @Column(name = "run_id", length = 128, nullable = false) String runId;
    @Column(nullable = false) long revision;
    @Column(name = "analysis_rounds", nullable = false) int analysisRounds;
    @Column(name = "max_rounds", nullable = false) int maxRounds;
    @Column(name = "observation_revision", nullable = false) long observationRevision;
    @Column(name = "consumed_revision", nullable = false) long consumedRevision;
    @Column(name = "observation_hash", length = 64) String observationHash;
    @Column(name = "analysis_action", length = 16) String analysisAction;
    @Column(name = "analysis_reason", length = 64) String analysisReason;
    @Column(nullable = false) boolean terminal;
    protected RuntimeEvidencePartitionEntity() {}
}
