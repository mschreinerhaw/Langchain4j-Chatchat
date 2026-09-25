package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Shared rolling health signal; it never overrides tenant and evidence admission policy. */
@Component
public class AgentHealthTracker {
    private final AgentHealthStateStore store;
    private final AgentHealthStateStore local = new InMemoryAgentHealthStateStore();

    public AgentHealthTracker() { this.store = local; }

    @Autowired
    public AgentHealthTracker(ObjectProvider<AgentHealthStateStore> stores) {
        AgentHealthStateStore supplied = stores.getIfAvailable();
        this.store = supplied == null ? local : supplied;
    }

    public boolean available(AgentDescriptor agent) {
        if (agent.origin() == AgentDescriptor.Origin.LOCAL) return true;
        return System.currentTimeMillis() >= state(agent.agentId()).openUntilEpochMs();
    }

    public int latencyPenalty(AgentDescriptor agent) {
        AgentHealthStateStore.State state = state(agent.agentId());
        if (state.samples() == 0) return 0;
        long sla = number(agent.metadata().get("slaLatencyMs"), 0);
        return sla > 0 && state.ewmaLatencyMs() > sla ? 1000 : 0;
    }

    public void record(AgentDescriptor agent, AgentExecutionOutcome outcome, long latencyMs) {
        if (agent.origin() == AgentDescriptor.Origin.LOCAL) return;
        boolean failed = outcome == null || switch (outcome.status()) {
            case FAILED, TIMED_OUT, BLOCKED -> true;
            case PARTIAL -> outcome.claims().isEmpty();
            default -> false;
        };
        long now = System.currentTimeMillis();
        if (store != local) local.record(agent.agentId(), failed, latencyMs, now);
        try { store.record(agent.agentId(), failed, latencyMs, now); }
        catch (RuntimeException unavailable) {
            if (store == local) throw unavailable;
            // Health storage failure must not turn an Agent result into a failed analysis.
        }
    }

    public Map<String, Object> snapshot(String agentId) {
        AgentHealthStateStore.State state = state(agentId);
        if (state.samples() == 0) return Map.of("state", "UNKNOWN", "samples", 0);
        return Map.of("state", System.currentTimeMillis() < state.openUntilEpochMs() ? "OPEN" : "AVAILABLE",
            "samples", state.samples(), "ewmaLatencyMs", state.ewmaLatencyMs(),
            "consecutiveFailures", state.consecutiveFailures(), "openUntil", state.openUntilEpochMs());
    }

    private AgentHealthStateStore.State state(String agentId) {
        AgentHealthStateStore.State fallback = local.read(agentId);
        if (store == local) return fallback;
        try {
            AgentHealthStateStore.State durable = store.read(agentId);
            return durable == null || durable.samples() < fallback.samples() ? fallback : durable;
        } catch (RuntimeException unavailable) { return fallback; }
    }

    private long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

}
