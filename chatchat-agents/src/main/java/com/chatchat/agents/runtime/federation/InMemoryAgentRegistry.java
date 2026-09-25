package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentProvider;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local catalog. A persistent enterprise adapter may replace this port without changing Runtime. */
@Component
public class InMemoryAgentRegistry implements AgentRegistryPort {
    private final ConcurrentMap<String, AgentDescriptor> descriptors = new ConcurrentHashMap<>();

    public InMemoryAgentRegistry(List<AgentProvider> providers) {
        if (providers != null) providers.forEach(provider -> register(provider.descriptor()));
    }

    @Override public Optional<AgentDescriptor> find(String agentId) {
        if (agentId == null || agentId.isBlank()) return Optional.empty();
        return Optional.ofNullable(descriptors.get(agentId.trim()));
    }

    @Override public List<AgentDescriptor> findByCapability(CapabilityId capability) {
        if (capability == null) return List.of();
        return descriptors.values().stream().filter(AgentDescriptor::enabled)
            .filter(descriptor -> descriptor.provides(capability))
            .sorted(Comparator.comparingInt(AgentDescriptor::priority).reversed()
                .thenComparing(AgentDescriptor::agentId))
            .toList();
    }

    @Override public List<AgentDescriptor> list() {
        return descriptors.values().stream().sorted(Comparator.comparing(AgentDescriptor::agentId)).toList();
    }

    @Override public void register(AgentDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("agent descriptor is required");
        descriptors.put(descriptor.agentId(), descriptor);
    }

    @Override public void remove(String agentId) {
        if (agentId != null) descriptors.remove(agentId.trim());
    }
}
