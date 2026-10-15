package com.chatchat.agents.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;

/** Resolves committed references through existing governed evidence adapters; no new store. */
@FunctionalInterface
public interface CodingEvidenceVerifier {
    boolean isCommitted(KernelDataScope scope, String reference);
}
