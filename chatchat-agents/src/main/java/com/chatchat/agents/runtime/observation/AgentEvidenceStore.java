package com.chatchat.agents.runtime.observation;

import java.util.Optional;

/**
 * Stores large agent evidence outside the JVM heap and RocksDB run records.
 */
public interface AgentEvidenceStore {

    boolean isEnabled();

    void put(String documentId,
             String tenantId,
             String runId,
             String evidenceId,
             String json);

    Optional<String> get(String documentId);

    void delete(String documentId);
}
