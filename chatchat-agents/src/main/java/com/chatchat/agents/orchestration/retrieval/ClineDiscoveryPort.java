package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Cline proposes search terms or confirms identities from a Runtime-owned search receipt. */
public interface ClineDiscoveryPort {
    enum Stage { SEARCH_TERMS, CONFIRM_ASSETS }

    Map<String, Object> propose(KernelDataScope scope, String modelName, int stepId, Stage stage,
        Map<String, Object> input, BooleanSupplier cancelled);
}
