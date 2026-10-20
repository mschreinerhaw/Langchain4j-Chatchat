package com.chatchat.api.runtime.coding.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resource-size routing only; aliases and reasoning are explicit trusted deployment settings. */
public final class CodingExecutionProfile {
    public static final String VERSION = "runtime_os.coding.execution_profile.v1";
    private CodingExecutionProfile() { }

    public static Map<String,Object> select(CodingAnalysisProperties policy, int sourceCount, long inputRows) {
        if (sourceCount < 1 || sourceCount > com.chatchat.common.runtime.coding.CodingProtocol.MAX_ANALYSIS_SOURCES || inputRows < 0
                || policy.getSimpleMaxSources() < 1 || policy.getSimpleMaxSources() > 8
                || policy.getSimpleMaxInputRows() < 0 || policy.getSimpleMaxInputRows() > 1_000_000)
            throw new IllegalArgumentException("Invalid Coding complexity configuration or input size");
        boolean simple = sourceCount <= policy.getSimpleMaxSources()
            && inputRows <= policy.getSimpleMaxInputRows();
        String alias = simple ? policy.getSimpleModelName() : policy.getComplexModelName();
        String effort = simple ? policy.getSimpleReasoningEffort() : policy.getComplexReasoningEffort();
        var profile = new LinkedHashMap<String,Object>();
        profile.put("schemaVersion", VERSION);
        profile.put("complexity", simple ? "SIMPLE" : "COMPLEX");
        profile.put("sourceCount", sourceCount);
        profile.put("inputRows", inputRows);
        if (alias != null && !alias.isBlank()) {
            if (!alias.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
                throw new IllegalArgumentException("Coding model alias is invalid");
            profile.put("selectedModel", alias);
        }
        if (effort != null && !effort.isBlank()) {
            if (!Set.of("none", "minimal", "low", "medium", "high", "xhigh").contains(effort))
                throw new IllegalArgumentException("Coding reasoning effort is unsupported");
            profile.put("reasoningEffort", effort);
        }
        return Map.copyOf(profile);
    }
}
