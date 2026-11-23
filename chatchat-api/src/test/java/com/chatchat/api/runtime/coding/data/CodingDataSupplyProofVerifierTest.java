package com.chatchat.api.runtime.coding.data;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodingDataSupplyProofVerifierTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconcilesAnExistingNativeReceiptOnlyWhenItsExactPersistedOrderMatches(boolean supplement) {
        var scope = new KernelDataScope("tenant", "user", null, null, "run", null, Map.of());
        var evidence = mock(JdbcRuntimeEvidenceStore.class);
        var verifier = new CodingDataSupplyProofVerifier(evidence);
        String operation = NativePublishedTemplate.OPERATION + (supplement ? "-" + "a".repeat(64) : "");
        var payload = Map.<String,Object>of("taskId", "task", "operationId", operation, "executorStopped", true);
        var record = new EvidenceRecord(null, "receipt", scope, "CODING_DATA_RECEIPT", "NativeS3CodingAdapter",
            "original", payload, null, 1, Map.of());
        when(evidence.find(scope, "receipt")).thenReturn(Optional.of(record));
        when(evidence.contentHash(payload)).thenReturn("canonical");
        when(evidence.contentHashInPersistedOrder(payload)).thenReturn("original");
        assertThat(verifier.confirmsStopped(scope, "task", operation, "runtime-data:" + operation,
            "runtime-evidence:receipt")).isTrue();
        assertThat(verifier.confirmsStopped(scope, "another-task", operation, "runtime-data:" + operation,
            "runtime-evidence:receipt")).isFalse();
        when(evidence.contentHashInPersistedOrder(payload)).thenReturn("changed");
        assertThat(verifier.confirmsStopped(scope, "task", operation, "runtime-data:" + operation,
            "runtime-evidence:receipt")).isFalse();
    }
}
