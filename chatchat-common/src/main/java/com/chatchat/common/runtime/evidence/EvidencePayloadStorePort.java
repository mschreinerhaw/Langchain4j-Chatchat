package com.chatchat.common.runtime.evidence;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.Optional;

/** Payload offload boundary used when full evidence content must stay outside run checkpoints. */
public interface EvidencePayloadStorePort extends RuntimeProtocolPort {
    String PROTOCOL_ID = "runtime_os.evidence.payload-store.v1";

    boolean isEnabled();

    void put(String documentId, String tenantId, String runId, String evidenceId, String json);

    Optional<String> get(String documentId);

    void delete(String documentId);
}
