package com.chatchat.chat.interaction.service;

import com.chatchat.agents.runtime.plan.RuntimeSemanticPolicy;
import com.chatchat.agents.runtime.plan.RuntimeSemanticPolicySource;
import com.chatchat.common.tool.ToolWorkflowContractCatalog;
import com.chatchat.enterprise.repository.mcp.CapabilityRegistryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Loads one immutable policy snapshot when an Agent runtime is created. */
@Component
public class DatabaseRuntimeSemanticPolicySource implements RuntimeSemanticPolicySource {
    private final RuntimeSemanticPolicyRepository policies;
    private final ObjectMapper mapper;
    private final CapabilityRegistryRepository capabilities;
    private final ToolWorkflowContractCatalog contracts;
    private final RuntimeSemanticLegacyToolRepository legacyTools;

    public DatabaseRuntimeSemanticPolicySource(RuntimeSemanticPolicyRepository policies, ObjectMapper mapper) {
        this(policies, mapper, null, null, null);
    }

    @Autowired
    public DatabaseRuntimeSemanticPolicySource(RuntimeSemanticPolicyRepository policies, ObjectMapper mapper,
                                               CapabilityRegistryRepository capabilities,
                                               ToolWorkflowContractCatalog contracts,
                                               RuntimeSemanticLegacyToolRepository legacyTools) {
        this.policies = policies;
        this.mapper = mapper;
        this.capabilities = capabilities;
        this.contracts = contracts;
        this.legacyTools = legacyTools;
    }

    /** Mandatory protocol policy is checked at startup; optional tool roles remain independently publishable. */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyPublishedPolicy() {
        snapshot();
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeSemanticPolicy snapshot() {
        RuntimeSemanticPolicyEntity active = policies.findById("default")
            .orElseThrow(() -> new IllegalStateException("Runtime semantic policy is not published"));
        try {
            Map<String, Object> values = mapper.readValue(active.getPolicyJson(), new TypeReference<>() { });
            RuntimeSemanticPolicy policy = RuntimeSemanticPolicy.from(values);
            if (legacyTools != null) {
                policy = policy.withLegacyExecutionTools(legacyTools.findAll().stream()
                    .map(RuntimeSemanticLegacyTool::getToolName)
                    .collect(java.util.stream.Collectors.toSet()));
            }
            if (capabilities == null || contracts == null) return policy;
            Map<String, Set<String>> roles = new LinkedHashMap<>();
            capabilities.findAll().stream()
                .filter(entry -> Set.of("PUBLISHED", "DEPRECATED").contains(entry.getStatus()))
                .forEach(entry -> contracts.findActive(entry.getServiceId(), entry.getToolName(), null)
                    .ifPresent(contract -> {
                        Object raw = contract.extensions().get("runtimeRoles");
                        if (!(raw instanceof List<?> declared)) return;
                        Set<String> names = new LinkedHashSet<>();
                        for (Object item : declared) {
                            if (!(item instanceof String name) || name.isBlank()) {
                                throw new IllegalArgumentException("Invalid published Runtime role");
                            }
                            names.add(name.trim().toUpperCase(Locale.ROOT));
                        }
                        roles.put(entry.getToolName(), names);
                    }));
            return policy.withPublishedRoles(roles);
        } catch (Exception invalidPolicy) {
            throw new IllegalStateException("Invalid Runtime semantic policy", invalidPolicy);
        }
    }
}
