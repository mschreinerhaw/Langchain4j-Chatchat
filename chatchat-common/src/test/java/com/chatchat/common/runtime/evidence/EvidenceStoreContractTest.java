package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceStoreContractTest {

    private final KernelDataScope scope = new KernelDataScope(
        "tenant-1", "user-1", "request-1", "conversation-1", "run-1", "prod", Map.of());

    @Test
    void evidencePortsParticipateInRuntimeProtocolComposition() {
        assertThat(RuntimeProtocolPort.class).isAssignableFrom(EvidenceStorePort.class);
        assertThat(RuntimeProtocolPort.class).isAssignableFrom(EvidencePayloadStorePort.class);
        assertThat(EvidenceStorePort.PROTOCOL_ID).isEqualTo("runtime_os.evidence.store.v1");
    }

    @Test
    void evidenceModelsArePartitionedVersionedAndImmutable() {
        EvidenceRecord record = new EvidenceRecord(null, "evidence-1", scope, "tool_result",
            "analysis-node-1", "sha256-value", Map.of("answer", 42), null, 100L,
            Map.of("classification", "internal"));
        EvidenceLineage lineage = new EvidenceLineage(null, record.evidenceId(), scope,
            List.of("source-1", "source-1"), "task-1", "analysis-node-1", "tool-call-1",
            Map.of());
        EvidenceSnapshot snapshot = new EvidenceSnapshot(null, "snapshot-1", scope, 1L,
            List.of(record.evidenceId(), record.evidenceId()), 200L, Map.of());

        assertThat(record.schemaVersion()).isEqualTo(EvidenceRecord.SCHEMA_VERSION);
        assertThat(record.partitionKey()).isEqualTo("tenant-1:run-1");
        assertThat(lineage.parentEvidenceIds()).containsExactly("source-1");
        assertThat(snapshot.evidenceIds()).containsExactly("evidence-1");
        assertThatThrownBy(() -> record.payload().put("forged", true))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evidenceCannotExistWithoutPayload() {
        assertThatThrownBy(() -> new EvidenceRecord(null, "evidence-1",
            KernelDataScope.system("request-1"), "tool_result", "node-1", "sha", Map.of(),
            null, 1L, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
        assertThatThrownBy(() -> new EvidenceQuery(
            new KernelDataScope(null, null, "request-1", null, null, null, Map.of()),
            List.of(), List.of(), 0L, 0L, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenant partition");
    }
}
