package com.chatchat.agents.runtime.analysis;

import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;

import java.util.Map;
import java.util.Optional;

/**
 * Lossless overflow store for loop-analysis working data and resumable summary checkpoints.
 * Implementations must isolate every operation by the runtime-owned tenant/run partition.
 */
public interface AnalysisEvidenceSpillStore {

    String SPILL_SCHEMA_VERSION = "analysis_evidence_spill.v1";
    String CHECKPOINT_SCHEMA_VERSION = "analysis_summary_checkpoint.v1";

    boolean isEnabled();

    SpillReference spill(GovernanceIsolationScope scope,
                         String evidenceId,
                         String contentSha256,
                         byte[] payload);

    byte[] read(GovernanceIsolationScope scope, SpillReference reference);

    Optional<String> readCheckpoint(GovernanceIsolationScope scope,
                                    String checkpointKey,
                                    String inputSha256);

    void checkpoint(GovernanceIsolationScope scope,
                    String checkpointKey,
                    String inputSha256,
                    String summaryJson);

    default void deletePartition(GovernanceIsolationScope scope) {
        // Optional lifecycle cleanup hook.
    }

    static AnalysisEvidenceSpillStore disabled() {
        return DisabledHolder.INSTANCE;
    }

    record SpillReference(String schemaVersion,
                          String resolver,
                          String storageKey,
                          String evidenceId,
                          String contentSha256,
                          long byteLength,
                          long createdAtEpochMs) {

        public SpillReference {
            schemaVersion = SPILL_SCHEMA_VERSION;
            resolver = resolver == null || resolver.isBlank() ? "ANALYSIS_SPILL_STORE" : resolver;
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "schemaVersion", schemaVersion,
                "resolver", resolver,
                "storageKey", storageKey,
                "evidenceId", evidenceId,
                "contentSha256", contentSha256,
                "byteLength", byteLength,
                "createdAtEpochMs", createdAtEpochMs
            );
        }
    }

    final class DisabledHolder {
        private static final AnalysisEvidenceSpillStore INSTANCE = new AnalysisEvidenceSpillStore() {
            @Override public boolean isEnabled() { return false; }
            @Override public SpillReference spill(GovernanceIsolationScope scope, String evidenceId,
                                                   String contentSha256, byte[] payload) {
                throw new IllegalStateException("Analysis evidence spill store is disabled");
            }
            @Override public byte[] read(GovernanceIsolationScope scope, SpillReference reference) {
                throw new IllegalStateException("Analysis evidence spill store is disabled");
            }
            @Override public Optional<String> readCheckpoint(GovernanceIsolationScope scope,
                                                              String checkpointKey,
                                                              String inputSha256) {
                return Optional.empty();
            }
            @Override public void checkpoint(GovernanceIsolationScope scope, String checkpointKey,
                                             String inputSha256, String summaryJson) { }
        };

        private DisabledHolder() { }
    }
}
