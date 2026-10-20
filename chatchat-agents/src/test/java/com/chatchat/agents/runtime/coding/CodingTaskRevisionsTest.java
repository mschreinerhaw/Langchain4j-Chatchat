package com.chatchat.agents.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingTaskRevisionsTest {
    private final KernelDataScope scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
    private final DurableCodingTaskService tasks = mock(DurableCodingTaskService.class);

    @Test void onlyJournalRevisionConflictsRefreshTheRevision() {
        var first = mock(CodingTaskOutcome.class); when(first.revision()).thenReturn(7L);
        var latest = mock(CodingTaskOutcome.class); when(latest.revision()).thenReturn(8L);
        when(tasks.get(scope, "task")).thenReturn(first, latest);
        var attempts = new AtomicInteger();
        long result = CodingTaskRevisions.mutate(tasks, scope, "task", revision -> {
            if (attempts.getAndIncrement() == 0) throw conflict("Coding task revision conflict");
            return revision;
        });
        assertThat(result).isEqualTo(8);
        assertThat(attempts).hasValue(2);
    }
    @Test void fencingAndUnknownExecutionAreNeverRetried() {
        when(tasks.get(scope, "task")).thenReturn(mock(CodingTaskOutcome.class));
        for (var error : new CodingContractException[] { conflict("Expired or fenced execution owner"),
                new CodingContractException(CodingErrorCode.EXECUTION_OUTCOME_UNKNOWN, "Reconciliation is required") }) {
            var attempts = new AtomicInteger();
            assertThatThrownBy(() -> CodingTaskRevisions.mutate(tasks, scope, "task", revision -> {
                attempts.incrementAndGet(); throw error;
            })).isSameAs(error);
            assertThat(attempts).hasValue(1);
        }
    }
    @Test void revisionContentionIsBounded() {
        when(tasks.get(scope, "task")).thenReturn(mock(CodingTaskOutcome.class));
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> CodingTaskRevisions.mutate(tasks, scope, "task", revision -> {
            attempts.incrementAndGet(); throw conflict("Coding task revision conflict");
        })).isInstanceOf(CodingContractException.class);
        assertThat(attempts).hasValue(8);
    }
    private static CodingContractException conflict(String message) {
        return new CodingContractException(CodingErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
