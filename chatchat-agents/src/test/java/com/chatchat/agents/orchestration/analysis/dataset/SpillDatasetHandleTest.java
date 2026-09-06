package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SpillDatasetHandleTest {
    @Test void capturesAndReadsPagesAcrossStoredPageBoundaries() {
        Map<String, byte[]> values = new ConcurrentHashMap<>();
        AnalysisEvidenceSpillStore store = new AnalysisEvidenceSpillStore() {
            @Override public boolean isEnabled() { return true; }
            @Override public SpillReference spill(GovernanceIsolationScope scope, String evidenceId,
                                                   String hash, byte[] payload) {
                String key = scope.partitionKey() + ":" + evidenceId;
                values.put(key, payload.clone());
                return new SpillReference(SPILL_SCHEMA_VERSION, "TEST", key, evidenceId, hash,
                    payload.length, System.currentTimeMillis());
            }
            @Override public byte[] read(GovernanceIsolationScope scope, SpillReference reference) {
                return values.get(reference.storageKey()).clone();
            }
            @Override public Optional<String> readCheckpoint(GovernanceIsolationScope scope,
                String checkpointKey, String inputSha256) { return Optional.empty(); }
            @Override public void checkpoint(GovernanceIsolationScope scope, String checkpointKey,
                String inputSha256, String summaryJson) { }
        };
        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < 2_501; index++) records.add(Map.of("id", index));
        var scope = GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation");
        var handle = SpillDatasetHandle.capture("dataset", new InMemoryDatasetHandle(records),
            store, scope, 1_000);

        assertThat(handle.descriptor()).containsEntry("pageCount", 3).containsEntry("recordCount", 2_501L);
        assertThat(handle.readPage(995, 20).rows()).extracting(row -> row.get("id"))
            .containsExactlyElementsOf(java.util.stream.IntStream.range(995, 1015).boxed().toList());
        assertThat(handle.readPage(2_500, 10).rows()).containsExactly(Map.of("id", 2_500));
    }
}
