package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.Optional;

/** Shared execution facts, not analytical decisions. Implementations commit CAS before returning. */
public interface RuntimeExecutionCheckpointPort {
    Optional<String> readExecutionCheckpoint(KernelDataScope scope, String key);
    boolean compareAndSetExecutionCheckpoint(KernelDataScope scope, String key, String expectedJson, String nextJson);
    /**
     * Runs a pure, bounded mutation under the same owner lock and transaction as the write.
     * The current value may be null. The result must not be null. Exceptions roll back the write.
     * No I/O, callbacks to this store or execution side effects are permitted inside the mutation.
     * Optional capability: never emulate this with an unlocked read followed by a write.
     */
    default String mutateExecutionCheckpoint(KernelDataScope scope, String key,
                                            java.util.function.UnaryOperator<String> mutation) {
        throw new UnsupportedOperationException("Transactional checkpoint mutation is unavailable");
    }
    /** Optional maintenance: never removes referenced receipts, claims or resource-budget identities. */
    default int cleanupUnreferencedExecutionPayloads() { return 0; }
}
