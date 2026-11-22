package com.chatchat.agents.orchestration.tool;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Obtains template choices and parameter proposals before Runtime execution. */
public interface ClineTemplateParameterPort {
    enum Selection { ALL_PUBLISHED, MATCHING_CANDIDATES }

    boolean enabledFor(String agentId);

    List<Map<String, Object>> propose(KernelDataScope scope, String modelName, int discoveryStepId,
        String query, List<Map<String, Object>> templates, Selection selection, BooleanSupplier cancelled);
}
