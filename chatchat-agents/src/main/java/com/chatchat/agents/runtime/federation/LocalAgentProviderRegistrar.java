package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentProvider;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/** Makes local providers discoverable in either the in-memory or enterprise registry. */
@Component
public class LocalAgentProviderRegistrar implements InitializingBean {
    private final AgentRegistryPort registry;
    private final List<AgentProvider> providers;

    public LocalAgentProviderRegistrar(AgentRegistryPort registry, List<AgentProvider> providers) {
        this.registry = registry;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    @Override public void afterPropertiesSet() {
        providers.forEach(provider -> registry.register(provider.descriptor()));
    }
}
