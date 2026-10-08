package com.chatchat.chat.interaction.service;

import com.chatchat.agents.runtime.plan.RuntimeSemanticPolicy;
import com.chatchat.agents.runtime.plan.RuntimeSemanticPolicySource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Loads one immutable policy snapshot when an Agent runtime is created. */
@Component
@RequiredArgsConstructor
public class DatabaseRuntimeSemanticPolicySource implements RuntimeSemanticPolicySource {
    private final RuntimeSemanticPolicyRepository policies;
    private final ObjectMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public RuntimeSemanticPolicy snapshot() {
        RuntimeSemanticPolicyEntity active = policies.findById("default")
            .orElseThrow(() -> new IllegalStateException("Runtime semantic policy is not published"));
        try {
            Map<String, Object> values = mapper.readValue(active.getPolicyJson(), new TypeReference<>() { });
            return RuntimeSemanticPolicy.from(values);
        } catch (Exception invalidPolicy) {
            throw new IllegalStateException("Invalid Runtime semantic policy", invalidPolicy);
        }
    }
}
