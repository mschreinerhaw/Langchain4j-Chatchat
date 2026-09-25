package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.List;

/** Deterministic policy filter. Model ranking, when added, may only rank this admitted set. */
@Component
public class AgentCapabilityPlanner {
    private final AgentRegistryPort registry;
    private final AgentHealthTracker health;

    @Autowired
    public AgentCapabilityPlanner(AgentRegistryPort registry, AgentHealthTracker health) {
        this.registry = registry;
        this.health = health;
    }

    AgentCapabilityPlanner(AgentRegistryPort registry) { this(registry, new AgentHealthTracker()); }

    public List<AgentDescriptor> candidates(AgentExecutionRequest request) {
        return registry.findByCapability(request.capability()).stream()
            .filter(agent -> targetAllowed(agent, request))
            .filter(agent -> agent.supportsExecutionMode(request.executionMode()))
            .filter(agent -> tenantAllowed(agent, request))
            .filter(agent -> domainsAllowed(agent, request))
            .filter(agent -> evidenceAllowed(agent, request))
            .filter(health::available)
            .filter(agent -> agent.outputSchema().equals(request.outputContract().schema()))
            .sorted(Comparator.comparingInt((AgentDescriptor agent) ->
                    agent.priority() - health.latencyPenalty(agent)).reversed()
                .thenComparingInt(agent -> trustRank(agent.trustLevel()))
                .thenComparing(AgentDescriptor::agentId))
            .toList();
    }

    private boolean targetAllowed(AgentDescriptor agent, AgentExecutionRequest request) {
        Object target = request.metadata().get(AgentExecutionRequest.TARGET_AGENT_METADATA_KEY);
        return target == null || String.valueOf(target).isBlank() || agent.agentId().equals(target);
    }

    private boolean tenantAllowed(AgentDescriptor agent, AgentExecutionRequest request) {
        if (agent.origin() == AgentDescriptor.Origin.LOCAL) {
            Object skillId = request.metadata().get("localSkillId");
            return !(skillId instanceof String value) || value.isBlank()
                || agent.agentId().equals("local.skill." + value);
        }
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
            .map(value -> String.valueOf(value.attributes().getOrDefault("sourceType",
                value.getClass().getSimpleName())))
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
