package com.chatchat.agents.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;

/** Future execution-node adapter: verify immutable stop evidence outside the journal transaction. */
@FunctionalInterface
public interface CodingExecutionProofVerifier {
    boolean confirmsStopped(KernelDataScope scope, String taskId, String operationId,
                            String executionHandle, String proofRef);
}
