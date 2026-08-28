package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unified Evidence Store boundary for tool evidence, analysis output, provenance and review snapshots.
 * Persistence, indexing and payload offloading remain adapter responsibilities.
 */
public interface EvidenceStorePort extends RuntimeProtocolPort {
    String PROTOCOL_ID = "runtime_os.evidence.store.v1";

    EvidenceRegistration register(EvidenceRecord evidence, EvidenceLineage lineage);

    Optional<EvidenceRecord> find(KernelDataScope scope, String evidenceId);

    List<EvidenceRecord> query(EvidenceQuery query);

    Optional<EvidenceLineage> lineage(KernelDataScope scope, String evidenceId);

    EvidenceSnapshot createSnapshot(
        KernelDataScope scope,
        String snapshotId,
        List<String> evidenceIds,
        Map<String, Object> metadata
    );

    Optional<EvidenceSnapshot> snapshot(KernelDataScope scope, String snapshotId);

    boolean delete(KernelDataScope scope, String evidenceId);
}
