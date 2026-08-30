package com.chatchat.agents.orchestration.lifecycle;

import com.chatchat.agents.orchestration.planning.AgentRuntimeGuard;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Compiles request limits and a pinned DAG contract into one immutable execution scope. */
public final class AgentRuntimeAttributeCompiler {
    private final AgentRuntimeGuard runtimeGuard;
    private final Supplier<DagGovernanceContractProvider> contractProvider;
    private final String runIdAttribute;
    private final String maxStepsAttribute;
    private final String maxToolCallsAttribute;
    private final String timeoutAttribute;

    public AgentRuntimeAttributeCompiler(AgentRuntimeGuard runtimeGuard,
                                         Supplier<DagGovernanceContractProvider> contractProvider,
                                         String runIdAttribute,
                                         String maxStepsAttribute,
                                         String maxToolCallsAttribute,
                                         String timeoutAttribute) {
        this.runtimeGuard = runtimeGuard;
        this.contractProvider = contractProvider;
        this.runIdAttribute = runIdAttribute;
        this.maxStepsAttribute = maxStepsAttribute;
        this.maxToolCallsAttribute = maxToolCallsAttribute;
        this.timeoutAttribute = timeoutAttribute;
    }

    public Map<String, Object> compile(AgentRunRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>(
            request.getAttributes() == null ? Map.of() : request.getAttributes());
        putText(attributes, runIdAttribute, request.getRunId());
        if (request.getSkillId() != null && !request.getSkillId().isBlank()) {
            attributes.putIfAbsent("agentId", request.getSkillId().trim());
        }
        if (request.getMaxSteps() != null) attributes.put(maxStepsAttribute, request.getMaxSteps());
        if (request.getMaxToolCalls() != null) {
            attributes.put(maxToolCallsAttribute, request.getMaxToolCalls());
        }
        attributes.put(timeoutAttribute, request.getTimeoutMs() == null
            ? AgentRunRequest.DEFAULT_TIMEOUT_MS : request.getTimeoutMs());
        pinContract(attributes);
        return runtimeGuard.attributesWithDeadline(attributes);
    }

    public void pinContract(Map<String, Object> attributes) {
        if (attributes == null
            || attributes.containsKey(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE)) return;
        attributes.put(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE,
            contractProvider.get().activeContract().toRuntimeAttribute());
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
