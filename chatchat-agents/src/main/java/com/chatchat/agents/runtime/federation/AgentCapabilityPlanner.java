package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Deterministic policy filter. Model ranking, when added, may only rank this admitted set. */
@Component
public class AgentCapabilityPlanner {
    private final AgentRegistryPort registry;

    public AgentCapabilityPlanner(AgentRegistryPort registry) { this.registry = registry; }

    public List<AgentDescriptor> candidates(AgentExecutionRequest request) {
        return registry.findByCapability(request.capability()).stream()
            .filter(agent -> tenantAllowed(agent, request))
            .filter(agent -> domainsAllowed(agent, request))
            .filter(agent -> evidenceAllowed(agent, request))
            .filter(agent -> agent.outputSchema().equals(request.outputContract().schema()))
            .sorted(Comparator.comparingInt(AgentDescriptor::priority).reversed()
                .thenComparingInt(agent -> trustRank(agent.trustLevel()))
                .thenComparing(AgentDescriptor::agentId))
            .toList();
    }

    private boolean tenantAllowed(AgentDescriptor agent, AgentExecutionRequest request) {
        if (agent.origin() == AgentDescriptor.Origin.LOCAL) return true;
        Object grants = agent.metadata().get("allowedTenantIds");
        if (!(grants instanceof Iterable<?> values)) return false;
        for (Object value : values) {
            if (request.scope().tenantId().equals(value)) return true;
        }
        return false;
    }

    private boolean domainsAllowed(AgentDescriptor agent, AgentExecutionRequest request) {
        var required = request.constraints().allowedDataDomains();
        if (required.isEmpty()) return true;
        if (agent.origin() == AgentDescriptor.Origin.LOCAL) return true;
        return !agent.allowedDataDomains().isEmpty() && agent.allowedDataDomains().containsAll(required);
    }

    private boolean evidenceAllowed(AgentDescriptor agent, AgentExecutionRequest request) {
        if (agent.origin() == AgentDescriptor.Origin.LOCAL || request.evidence().evidence().isEmpty()) return true;
        if (agent.allowedEvidenceTypes().isEmpty()) return false;
        return request.evidence().evidence().stream()
            .map(value -> value.getClass().getSimpleName())
            .allMatch(agent.allowedEvidenceTypes()::contains);
    }

    private int trustRank(AgentDescriptor.TrustLevel trust) {
        return switch (trust) {
            case INTERNAL -> 0;
            case GROUP_TRUSTED -> 1;
            case PARTNER -> 2;
            case UNTRUSTED -> 3;
        };
    }
}
