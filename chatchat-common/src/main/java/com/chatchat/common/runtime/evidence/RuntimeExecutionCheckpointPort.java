package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.Optional;

/** Shared execution facts, not analytical decisions. Implementations commit CAS before returning. */
public interface RuntimeExecutionCheckpointPort {
    Optional<String> readExecutionCheckpoint(KernelDataScope scope, String key);
    boolean compareAndSetExecutionCheckpoint(KernelDataScope scope, String key, String expectedJson, String nextJson);
}
