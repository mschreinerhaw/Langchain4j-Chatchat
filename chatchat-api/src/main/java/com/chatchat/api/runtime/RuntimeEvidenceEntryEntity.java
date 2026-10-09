package com.chatchat.api.runtime;

import jakarta.persistence.*;

/** Immutable evidence facts and lineage are separate from the mutable controller partition. */
@Entity
@Table(name = "runtime_evidence_entry", indexes = @Index(name = "idx_runtime_evidence_partition", columnList = "partition_id,entry_kind,stored_at"))
public class RuntimeEvidenceEntryEntity {
    @Id @Column(length = 64) String id;
    @Column(name = "partition_id", length = 64, nullable = false) String partitionId;
    @Column(name = "entry_kind", length = 16, nullable = false) String entryKind;
    @Column(name = "entry_id", length = 256, nullable = false) String entryId;
    @Column(nullable = false) long revision;
    @Column(name = "stored_at", nullable = false) long storedAt;
    @Column(name = "occurred_at", nullable = false) long occurredAt;
    @Column(name = "evidence_type", length = 128) String evidenceType;
    @Column(name = "source_node", length = 256) String sourceNode;
    @Column(length = 64, nullable = false) String sha256;
    @Column(name = "record_json", length = org.hibernate.Length.LONG32, nullable = false) String recordJson;
    @Column(name = "lineage_json", length = org.hibernate.Length.LONG32) String lineageJson;
    protected RuntimeEvidenceEntryEntity() {}
}
