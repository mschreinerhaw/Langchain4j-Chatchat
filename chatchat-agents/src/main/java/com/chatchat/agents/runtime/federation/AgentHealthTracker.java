package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local rolling health signal; it never overrides tenant and evidence admission policy. */
@Component
public class AgentHealthTracker {
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean available(AgentDescriptor agent) {
        if (agent.origin() == AgentDescriptor.Origin.LOCAL) return true;
        return System.currentTimeMillis() >= states.computeIfAbsent(agent.agentId(), ignored -> new State()).openUntil;
    }

    public int latencyPenalty(AgentDescriptor agent) {
        State state = states.get(agent.agentId());
        if (state == null || state.samples == 0) return 0;
        long sla = number(agent.metadata().get("slaLatencyMs"), 0);
        return sla > 0 && state.ewmaLatencyMs > sla ? 1000 : 0;
    }

    public void record(AgentDescriptor agent, AgentExecutionOutcome outcome, long latencyMs) {
        if (agent.origin() == AgentDescriptor.Origin.LOCAL) return;
        State state = states.computeIfAbsent(agent.agentId(), ignored -> new State());
        synchronized (state) {
            state.samples++;
            state.ewmaLatencyMs = state.samples == 1 ? latencyMs : Math.round(state.ewmaLatencyMs * .8 + latencyMs * .2);
            boolean failed = outcome == null || switch (outcome.status()) {
                case FAILED, TIMED_OUT, BLOCKED -> true;
                case PARTIAL -> outcome.claims().isEmpty();
                default -> false;
            };
            state.consecutiveFailures = failed ? state.consecutiveFailures + 1 : 0;
            if (state.consecutiveFailures >= 3) state.openUntil = System.currentTimeMillis() + 30_000;
            if (!failed) state.openUntil = 0;
        }
    }

    public Map<String, Object> snapshot(String agentId) {
        State state = states.get(agentId);
        if (state == null) return Map.of("state", "UNKNOWN", "samples", 0);
        synchronized (state) {
            return Map.of("state", System.currentTimeMillis() < state.openUntil ? "OPEN" : "AVAILABLE",
                "samples", state.samples, "ewmaLatencyMs", state.ewmaLatencyMs,
                "consecutiveFailures", state.consecutiveFailures, "openUntil", state.openUntil);
        }
    }

    private long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static final class State {
        int samples;
        int consecutiveFailures;
        long ewmaLatencyMs;
        volatile long openUntil;
    }
}
